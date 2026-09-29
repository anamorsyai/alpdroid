/*
 * pty_bridge: allocates a real Unix PTY, execs a child process attached to its slave side as
 * controlling terminal, then relays bytes between the PTY master and this process's own
 * stdin/stdout (which Kotlin already wired to a normal pipe via ProcessBuilder).
 *
 * Why this exists: the Android SDK exposes no public API to fork a child with a controlling
 * terminal set before exec (no posix_spawn-style pre-exec hook, no forkpty()). A plain
 * ProcessBuilder subprocess only gets pipes, and many interactive programs (proot's own guest
 * shell included — anything checking isatty()/using termios, e.g. vim, top, ash's line editing)
 * behave differently or refuse raw mode over a pipe. This is the minimal native piece needed to
 * give the guest process a real terminal; everything else (rendering, key handling, ANSI parsing)
 * is plain Kotlin on the other side of the pipe.
 *
 * Usage: pty_bridge <control-fifo-path|-> <rows> <cols> -- <cmd> [args...]
 *
 * The control channel is a plain named pipe (FIFO) Kotlin creates with mkfifo() before
 * spawning this process — a side channel for window-resize notifications, kept separate from
 * the data stream so a resize can never collide with terminal data (e.g. a literal Ctrl-A byte
 * in the output). Pass "-" to run without one (resize just won't work).
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#ifndef TIOCGPTN
#define TIOCGPTN 0x80045430
#endif
#ifndef TIOCSPTLCK
#define TIOCSPTLCK 0x40045431
#endif
#ifndef TIOCSCTTY
#define TIOCSCTTY 0x540E
#endif

static int open_control_fifo(const char *path) {
    if (path == NULL || path[0] == '\0' || strcmp(path, "-") == 0) return -1;
    // O_NONBLOCK on a FIFO open means it succeeds immediately regardless of whether a writer
    // is attached yet — Kotlin opens its (blocking) write end shortly after this process
    // starts, and that open() call is what actually pairs the two ends up.
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    return fd; // -1 on failure is fine; caller just runs without resize support
}

static int open_pty_master(void) {
    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (master < 0) return -1;
    int unlock = 0;
    if (ioctl(master, TIOCSPTLCK, &unlock) != 0) {
        close(master);
        return -1;
    }
    return master;
}

static int pty_slave_path(int master, char *out, size_t out_len) {
    int ptn = 0;
    if (ioctl(master, TIOCGPTN, &ptn) != 0) return -1;
    snprintf(out, out_len, "/dev/pts/%d", ptn);
    return 0;
}

static void set_winsize(int master, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(master, TIOCSWINSZ, &ws);
}

// Set by sigchld_handler() (async-signal-safe: sig_atomic_t + write() to a pipe are the only
// things a signal handler is allowed to touch here) — read() on the pty master only returns EOF
// once *every* process holding the slave side has closed it, not just the shell we directly
// forked. A program that spawns its own detached background daemon (opencode's "Starting
// background server..." on its first run, in particular, is exactly this) can leave that daemon
// holding the slave open indefinitely if it doesn't close/redirect its inherited stdio when it
// forks — completely standard, well-behaved daemons do, but not everything does, and there is no
// portable way to force a well-behaved close from this side. Without this, that one orphaned
// process holding the pty open makes read(master) block here forever even though the shell the
// user actually asked to exit is long gone — indistinguishable, from Kotlin's side, from the
// whole session simply hanging, since this relay process itself never exits either. Watching for
// *our own direct child* (the shell) exiting instead — the same thing every real terminal emulator
// keys "the session is over" off of — sidesteps the ambiguity entirely: it needs to know nothing
// about what that child may or may not have spawned.
static volatile sig_atomic_t child_exited = 0;
static int self_pipe_write_fd = -1;

static void sigchld_handler(int sig) {
    (void) sig;
    child_exited = 1;
    if (self_pipe_write_fd >= 0) {
        char b = 0;
        ssize_t ignored = write(self_pipe_write_fd, &b, 1);
        (void) ignored; // best-effort wakeup; WNOHANG below still catches it on the next poll either way
    }
}

/** Returns 1 and fills *out_status if this function itself reaped `child` (the SIGCHLD path,
 *  the whole reason this function takes an out-param instead of just running to completion like
 *  it used to) — the caller must not waitpid() on `child` again in that case, it's already gone.
 *  Returns 0 (status left untouched) for every other way this loop ends (master EOF, a poll
 *  error, stdin/control both going away with nothing else left to do) — the caller still owns
 *  reaping `child` itself in those cases, same as before this function took on the SIGCHLD path
 *  at all. */
static int relay_loop(int master, int control_fd, pid_t child, int self_pipe_read_fd, int *out_status) {
    struct pollfd fds[4];
    int stdin_open = 1;
    char buf[4096];
    char ctlbuf[256];
    size_t ctl_len = 0;

    for (;;) {
        int n = 0;
        fds[n].fd = master;
        fds[n].events = POLLIN;
        int master_idx = n++;
        int stdin_idx = -1;
        if (stdin_open) {
            fds[n].fd = STDIN_FILENO;
            fds[n].events = POLLIN;
            stdin_idx = n++;
        }
        int control_idx = -1;
        if (control_fd >= 0) {
            fds[n].fd = control_fd;
            fds[n].events = POLLIN;
            control_idx = n++;
        }
        fds[n].fd = self_pipe_read_fd;
        fds[n].events = POLLIN;
        int sigchld_idx = n++;

        int ready = poll(fds, (nfds_t) n, -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (fds[master_idx].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t r = read(master, buf, sizeof(buf));
            if (r <= 0) break; // master closed: every process holding the pty is gone
            ssize_t off = 0;
            while (off < r) {
                ssize_t w = write(STDOUT_FILENO, buf + off, (size_t) (r - off));
                if (w <= 0) { if (errno == EINTR) continue; break; }
                off += w;
            }
        }

        if (fds[sigchld_idx].revents & POLLIN) {
            char drain[64];
            while (read(self_pipe_read_fd, drain, sizeof(drain)) > 0) {} // clear the wakeup byte(s)
        }
        if (child_exited) {
            int status = 0;
            pid_t reaped = waitpid(child, &status, WNOHANG);
            if (reaped == child) {
                // Drain whatever the shell already wrote before exiting — a final prompt redraw,
                // an error message — with one last non-blocking pass, so it isn't lost just
                // because this loop is about to end on our own initiative rather than master's EOF.
                for (;;) {
                    ssize_t r = read(master, buf, sizeof(buf));
                    if (r <= 0) break;
                    ssize_t off = 0;
                    while (off < r) {
                        ssize_t w = write(STDOUT_FILENO, buf + off, (size_t) (r - off));
                        if (w <= 0) { if (errno == EINTR) continue; break; }
                        off += w;
                    }
                }
                *out_status = status;
                return 1;
            }
            // SIGCHLD for some other reaped descendant (a background server forking off its own
            // children, most likely) — not our shell, so the session isn't actually over yet.
            child_exited = 0;
        }

        if (stdin_idx >= 0 && (fds[stdin_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t r = read(STDIN_FILENO, buf, sizeof(buf));
            if (r <= 0) {
                stdin_open = 0; // Kotlin closed the pipe; keep relaying master -> stdout
            } else {
                ssize_t off = 0;
                while (off < r) {
                    ssize_t w = write(master, buf + off, (size_t) (r - off));
                    if (w <= 0) { if (errno == EINTR) continue; break; }
                    off += w;
                }
            }
        }

        if (control_idx >= 0 && (fds[control_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
            if (ctl_len >= sizeof(ctlbuf) - 1) {
                // Only our own trusted Kotlin code ever writes here, always a short well-formed
                // "<rows> <cols>\n" line, so this should never actually happen — but ctl_len and
                // sizeof() are both unsigned, and without this guard a control message that
                // somehow filled the buffer with no newline in it would make the subtraction
                // below underflow to a huge count, turning the next read() into an out-of-bounds
                // write past the end of ctlbuf. Dropping the (already-garbage) buffered bytes is
                // safe: a resize is just a hint, never something losing one instance breaks.
                ctl_len = 0;
            }
            ssize_t r = read(control_fd, ctlbuf + ctl_len, sizeof(ctlbuf) - ctl_len - 1);
            if (r <= 0) {
                control_fd = -1; // control side gone; resize just stops working, not fatal
            } else {
                ctl_len += (size_t) r;
                ctlbuf[ctl_len] = '\0';
                char *line = ctlbuf;
                char *nl;
                while ((nl = strchr(line, '\n')) != NULL) {
                    *nl = '\0';
                    int rr = 0, cc = 0;
                    if (sscanf(line, "%d %d", &rr, &cc) == 2 && rr > 0 && cc > 0) {
                        set_winsize(master, rr, cc);
                    }
                    line = nl + 1;
                }
                size_t remaining = ctl_len - (size_t) (line - ctlbuf);
                memmove(ctlbuf, line, remaining);
                ctl_len = remaining;
            }
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: pty_bridge <control-fifo> <rows> <cols> -- <cmd> [args...]\n");
        return 2;
    }
    const char *control_path = argv[1];
    int rows = atoi(argv[2]);
    int cols = atoi(argv[3]);
    int sep = 4;
    if (strcmp(argv[sep], "--") != 0) {
        fprintf(stderr, "pty_bridge: expected '--' before command\n");
        return 2;
    }
    char **cmd_argv = &argv[sep + 1];
    if (cmd_argv[0] == NULL) {
        fprintf(stderr, "pty_bridge: no command given\n");
        return 2;
    }

    int master = open_pty_master();
    if (master < 0) {
        fprintf(stderr, "pty_bridge: failed to open /dev/ptmx: %s\n", strerror(errno));
        return 1;
    }
    char slave_path[64];
    if (pty_slave_path(master, slave_path, sizeof(slave_path)) != 0) {
        fprintf(stderr, "pty_bridge: failed to determine pty slave: %s\n", strerror(errno));
        return 1;
    }
    if (rows <= 0) rows = 24;
    if (cols <= 0) cols = 80;
    set_winsize(master, rows, cols);

    int control_fd = open_control_fifo(control_path);

    // Installed before fork() specifically so there's no window where the child could exit (an
    // exec failure, a command that's already missing) and deliver SIGCHLD before this process is
    // ready to catch it — a signal delivered with no handler installed is simply lost, not
    // queued, so relay_loop() would then have no way to ever learn that child had already exited.
    int self_pipe[2];
    if (pipe(self_pipe) != 0) {
        fprintf(stderr, "pty_bridge: pipe() failed: %s\n", strerror(errno));
        return 1;
    }
    fcntl(self_pipe[0], F_SETFL, fcntl(self_pipe[0], F_GETFL) | O_NONBLOCK);
    fcntl(self_pipe[1], F_SETFL, fcntl(self_pipe[1], F_GETFL) | O_NONBLOCK);
    self_pipe_write_fd = self_pipe[1];
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sigchld_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_NOCLDSTOP; // only "child exited", not every stop/continue of a job in it
    sigaction(SIGCHLD, &sa, NULL);

    pid_t child = fork();
    if (child < 0) {
        fprintf(stderr, "pty_bridge: fork failed: %s\n", strerror(errno));
        return 1;
    }
    if (child == 0) {
        // Child: become session leader, attach to the pty slave as controlling terminal, exec.
        // A regular Android app process gets a very restrictive (often near-zero) RLIMIT_MEMLOCK
        // and no CAP_IPC_LOCK — OpenSSL/LibreSSL's TLS handshake path calls mlock() to keep key
        // material from being swapped to disk, and treats that call failing with EPERM as fatal
        // rather than something to quietly proceed without. That failure happens once per TLS
        // context, before a single network byte is sent, entirely independent of which host is
        // being reached — matching a "Permission denied" that's identical across every different
        // server and network tried. Raising the soft limit to the process's own hard ceiling
        // (never past it — this can't grant a capability the process doesn't already have) is
        // free when the hard limit already allows it and a harmless no-op otherwise.
        struct rlimit memlock_limit;
        if (getrlimit(RLIMIT_MEMLOCK, &memlock_limit) == 0) {
            memlock_limit.rlim_cur = memlock_limit.rlim_max;
            setrlimit(RLIMIT_MEMLOCK, &memlock_limit);
        }
        setsid();
        int slave = open(slave_path, O_RDWR);
        if (slave < 0) {
            _exit(127);
        }
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        if (control_fd >= 0) close(control_fd);
        // Same reasoning as master/control_fd just above: neither the shell nor anything it goes
        // on to spawn has any business holding these open.
        close(self_pipe[0]);
        close(self_pipe[1]);
        execvp(cmd_argv[0], cmd_argv);
        _exit(127);
    }

    // Parent: pure relay between the pty master and our own stdio (Kotlin's pipes), plus the
    // resize side channel. Never touches the slave path directly. Both self_pipe ends stay open
    // here (unlike master/control_fd's read/write split above) — this pipe only ever signals
    // *this* process to itself (sigchld_handler writes, relay_loop's poll() reads), so there is
    // no "other side" of it to hand off and no unused end in the parent to close.
    signal(SIGPIPE, SIG_IGN);
    int status = 0;
    int reaped_in_loop = relay_loop(master, control_fd, child, self_pipe[0], &status);
    if (!reaped_in_loop) {
        // relay_loop ended some other way (master's own EOF, most commonly still — that still
        // happens immediately in the overwhelmingly common case of a program that doesn't spawn
        // anything holding the pty open past its own exit) — child is still ours to reap here,
        // exactly as before this file tracked SIGCHLD at all.
        waitpid(child, &status, 0);
    }
    close(master);
    if (control_fd >= 0) close(control_fd);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return 1;
}
