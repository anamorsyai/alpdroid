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
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
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
    // O_NOFOLLOW: a symlink planted at the UUID path would otherwise redirect the resize
    // channel; O_CLOEXEC keeps it out of the exec'd guest. S_ISFIFO check rejects a
    // squatted regular file. -1 on any failure is fine; caller runs without resize support.
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISFIFO(st.st_mode)) { close(fd); return -1; }
    return fd;
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

// P0: tab close used to orphan the whole guest. Kotlin's destroy() only kills this relay
// process itself; the guest (proot + shell + daemons) is our *child's* subtree and got
// reparented to init, still running. The child calls setsid() below, so its pgid == its pid:
// SIGTERM here SIGKILLs the entire guest process group, then wakes poll() so the SIGCHLD
// path reaps the child and this process exits promptly. destroyForcibly() on the Kotlin
// side is the final fallback if even this wedges.
static volatile sig_atomic_t term_requested = 0;
// sig_atomic_t (not pid_t): read by sigterm_handler asynchronously — plain pid_t is
// racy against the parent's post-fork assignment below.
static volatile sig_atomic_t g_child = -1;

static void wake_self_pipe(void) {
    if (self_pipe_write_fd >= 0) {
        char b = 0;
        ssize_t ignored = write(self_pipe_write_fd, &b, 1);
        (void) ignored; // best-effort wakeup; WNOHANG below still catches it on the next poll either way
    }
}

static void sigchld_handler(int sig) {
    (void) sig;
    child_exited = 1;
    wake_self_pipe();
}

static void sigterm_handler(int sig) {
    (void) sig;
    term_requested = 1;
    if (g_child > 0) {
        kill(-g_child, SIGKILL);
        kill(g_child, SIGKILL);
    }
    wake_self_pipe();
}

// Control channel (named pipe from Kotlin). One line per message:
//   "<rows> <cols>"  resize the pty
//   "INT"            SIGINT to the pty's foreground process group
//   "KILL"           SIGKILL to the whole guest process group
// INT/KILL signal the processes directly instead of going through the pty, so they still work when
// the pty's input queue is full and a ^C byte could never get in (a program in raw mode that
// stopped reading — a wedged server). Serviced from the main loop AND while a write to the pty is
// blocked, so an interrupt never waits behind input that cannot be delivered.
static int g_control_fd = -1;
static char g_ctlbuf[256];
static size_t g_ctl_len = 0;

static void signal_foreground(int master, int sig) {
    pid_t pg = tcgetpgrp(master);
    if (pg > 0) kill(-pg, sig);
    else if (g_child > 0) kill(-g_child, sig);
}

static void apply_affinity(unsigned long mask);

static void service_control(int master) {
    if (g_control_fd < 0) return;
    // Only our own trusted Kotlin code writes here, always short well-formed lines, so this should
    // never actually happen — but a message that filled the buffer with no newline in it would
    // otherwise make the unsigned subtraction below underflow into an out-of-bounds read().
    // Dropping the (garbage) buffered bytes is safe.
    if (g_ctl_len >= sizeof(g_ctlbuf) - 1) g_ctl_len = 0;
    ssize_t r = read(g_control_fd, g_ctlbuf + g_ctl_len, sizeof(g_ctlbuf) - g_ctl_len - 1);
    if (r < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return;
        g_control_fd = -1; // control side gone; resize/interrupt just stop working, not fatal
        return;
    }
    if (r == 0) { g_control_fd = -1; return; }
    g_ctl_len += (size_t) r;
    g_ctlbuf[g_ctl_len] = '\0';
    char *line = g_ctlbuf;
    char *nl;
    while ((nl = strchr(line, '\n')) != NULL) {
        *nl = '\0';
        int rr = 0, cc = 0;
        if (strcmp(line, "INT") == 0) {
            signal_foreground(master, SIGINT);
        } else if (strncmp(line, "AFF ", 4) == 0) {
            // "AFF <hex cpu mask>"; 0 means "all CPUs".
            unsigned long m = strtoul(line + 4, NULL, 16);
            apply_affinity(m == 0 ? ~0UL : m);
        } else if (strcmp(line, "KILL") == 0) {
            if (g_child > 0) { kill(-g_child, SIGKILL); kill(g_child, SIGKILL); }
        } else if (sscanf(line, "%d %d", &rr, &cc) == 2 && rr > 0 && cc > 0) {
            // Same clamp as argv parsing: set_winsize truncates to unsigned short.
            if (rr > 1000) rr = 1000;
            if (cc > 1000) cc = 1000;
            set_winsize(master, rr, cc);
        }
        line = nl + 1;
    }
    size_t remaining = g_ctl_len - (size_t) (line - g_ctlbuf);
    memmove(g_ctlbuf, line, remaining);
    g_ctl_len = remaining;
}

/** Restricts the guest's whole process tree (every thread of every descendant of `g_child`) to the CPUs in
 *  `mask` — the app's load balancer uses this to park busy background sessions on the phone's low-power
 *  cores and to give them all cores back. Affinity is reversible without privileges (unlike nice), and
 *  touches only our own session's processes. Best-effort: unreadable /proc entries are skipped. */
#define MAX_TRACKED_PIDS 16384
static void apply_affinity(unsigned long mask) {
    static int pids[MAX_TRACKED_PIDS], ppids[MAX_TRACKED_PIDS];
    static char in_tree[MAX_TRACKED_PIDS];
    if (g_child <= 0 || mask == 0) return;
    int count = 0;
    DIR *proc = opendir("/proc");
    if (proc == NULL) return;
    struct dirent *de;
    while ((de = readdir(proc)) != NULL && count < MAX_TRACKED_PIDS) {
        int pid = atoi(de->d_name);
        if (pid <= 0) continue;
        char path[64], line[512];
        snprintf(path, sizeof(path), "/proc/%d/stat", pid);
        FILE *f = fopen(path, "r");
        if (f == NULL) continue;
        int ppid = -1;
        if (fgets(line, sizeof(line), f) != NULL) {
            // "pid (comm) S ppid ..." — comm may contain spaces/parens, so anchor on the last ')'.
            char *rp = strrchr(line, ')');
            char state;
            if (rp != NULL) sscanf(rp + 1, " %c %d", &state, &ppid);
        }
        fclose(f);
        pids[count] = pid;
        ppids[count] = ppid;
        in_tree[count] = (pid == (int) g_child);
        count++;
    }
    closedir(proc);
    for (int changed = 1, guard = 0; changed && guard < 64; guard++) {
        changed = 0;
        for (int i = 0; i < count; i++) {
            if (in_tree[i]) continue;
            for (int j = 0; j < count; j++) {
                if (in_tree[j] && pids[j] == ppids[i]) { in_tree[i] = 1; changed = 1; break; }
            }
        }
    }
    for (int i = 0; i < count; i++) {
        if (!in_tree[i]) continue;
        char taskdir[64];
        snprintf(taskdir, sizeof(taskdir), "/proc/%d/task", pids[i]);
        DIR *tasks = opendir(taskdir);
        if (tasks == NULL) continue;
        struct dirent *te;
        while ((te = readdir(tasks)) != NULL) {
            int tid = atoi(te->d_name);
            if (tid > 0) syscall(__NR_sched_setaffinity, tid, sizeof(mask), &mask);
        }
        closedir(tasks);
    }
}

/** Writes all of buf to fd (blocking semantics). Returns 0 ok, -1 on a dead fd. */
static int write_all_stdout(const char *buf, ssize_t len) {
    ssize_t off = 0;
    while (off < len) {
        ssize_t w = write(STDOUT_FILENO, buf + off, (size_t) (len - off));
        // Dead stdout (EPIPE/EIO/EBADF) is session-over, not retryable: retrying it
        // would just spin.
        if (w <= 0) { if (errno == EINTR) continue; return -1; }
        off += w;
    }
    return 0;
}

/** Forwards whatever the pty master currently has to stdout. master is O_NONBLOCK.
 *  Returns 1 if data moved, 0 if nothing was ready (EAGAIN), -1 on master EOF/error or a
 *  dead stdout. */
static int pump_master(int master, char *buf, size_t cap) {
    ssize_t r = read(master, buf, cap);
    if (r < 0) return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? 0 : -1;
    if (r == 0) return -1;
    return write_all_stdout(buf, r) == 0 ? 1 : -1;
}

/** Writes input to the pty master without ever wedging the relay: master is O_NONBLOCK, and on a
 *  full pty buffer we keep draining the master's own output to stdout while waiting for room.
 *  A plain blocking write() here deadlocked on large pastes (the shell echoes input, the echo
 *  fills the master->stdout direction we were no longer reading, and the shell then stops
 *  reading input). Returns 0 ok, -1 if the session is over (dead pty/stdout, or SIGTERM). */
static int write_master_all(int master, const char *data, ssize_t len) {
    char tmp[4096];
    ssize_t off = 0;
    while (off < len) {
        if (term_requested) return -1;
        ssize_t w = write(master, data + off, (size_t) (len - off));
        if (w > 0) { off += w; continue; }
        if (w < 0 && errno == EINTR) continue;
        if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pfd[2];
            int np = 1;
            pfd[0].fd = master;
            pfd[0].events = POLLIN | POLLOUT;
            pfd[0].revents = 0;
            if (g_control_fd >= 0) {
                pfd[1].fd = g_control_fd;
                pfd[1].events = POLLIN;
                pfd[1].revents = 0;
                np = 2;
            }
            if (poll(pfd, (nfds_t) np, -1) < 0 && errno != EINTR) return -1;
            if (np == 2 && (pfd[1].revents & (POLLIN | POLLHUP | POLLERR))) service_control(master);
            if (pfd[0].revents & (POLLERR | POLLNVAL)) return -1;
            if (pfd[0].revents & POLLIN) {
                if (pump_master(master, tmp, sizeof(tmp)) < 0) return -1;
            }
            // Slave side closed (the guest exited or hung up the pty) and nothing is left to read:
            // the input can never be delivered. Without this, a pending write loops forever —
            // poll() reports POLLHUP at once and write() answers EAGAIN, not EIO — burning a core
            // and never getting back to the main loop (stdin, SIGCHLD, control messages), which
            // is exactly a session that looks frozen and ignores Ctrl+C.
            if ((pfd[0].revents & POLLHUP) && !(pfd[0].revents & POLLIN)) return -1;
            if (child_exited || term_requested) return -1;
            continue;
        }
        return -1; // EIO etc.: the slave side is gone
    }
    return 0;
}

/** Returns 1 and fills *out_status if this function itself reaped `child` (the SIGCHLD path,
 *  the whole reason this function takes an out-param instead of just running to completion like
 *  it used to) — the caller must not waitpid() on `child` again in that case, it's already gone.
 *  Returns 0 (status left untouched) for every other way this loop ends (master EOF, a poll
 *  error, stdin/control both going away with nothing else left to do) — the caller still owns
 *  reaping `child` itself in those cases, same as before this function took on the SIGCHLD path
 *  at all. */
static int relay_loop(int master, pid_t child, int self_pipe_read_fd, int *out_status) {
    struct pollfd fds[4];
    int stdin_open = 1;
    char buf[32768];

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
        if (g_control_fd >= 0) {
            fds[n].fd = g_control_fd;
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

        // SIGTERM (tab closed): guest group already SIGKILLed by the handler — stop relaying
        // and let the caller reap the child. Checked first so a flood of pty output can't
        // starve shutdown.
        if (term_requested) break;

        if (fds[master_idx].revents & (POLLIN | POLLHUP | POLLERR)) {
            // Non-blocking master: 0 means "nothing ready after all", -1 is master EOF
            // (every process holding the pty is gone) or a dead stdout.
            // Bounded drain: under heavy output one poll() + read() per chunk was three syscalls per
            // 4 KB; keep reading while data is immediately available, but never starve stdin/control.
            int pumped = 0, dead = 0;
            for (int i = 0; i < 8; i++) {
                pumped = pump_master(master, buf, sizeof(buf));
                if (pumped < 0) { dead = 1; break; }
                if (pumped == 0) break;
            }
            if (dead) break;
        }

        if (fds[sigchld_idx].revents & POLLIN) {
            char drain[64];
            while (read(self_pipe_read_fd, drain, sizeof(drain)) > 0) {} // clear the wakeup byte(s)
        }
        if (child_exited) {
            // Cleared *before* waitpid: a SIGCHLD landing between waitpid() and a later clear used to
            // be lost (flag reset after the fact), leaving the exit unnoticed until master EOF.
            child_exited = 0;
            int status = 0;
            pid_t reaped = waitpid(child, &status, WNOHANG);
            if (reaped == child) {
                // Drain whatever the shell already wrote before exiting — a final prompt redraw,
                // an error message — with one last bounded pass, so it isn't lost just
                // because this loop is about to end on our own initiative rather than master's EOF.
                // Bounded (200ms idle): a detached daemon holding the slave open with nothing to
                // say used to wedge this blocking read() forever, pinning the relay process.
                for (;;) {
                    struct pollfd pfd;
                    pfd.fd = master;
                    pfd.events = POLLIN;
                    if (poll(&pfd, 1, 200) <= 0) break;
                    if (pump_master(master, buf, sizeof(buf)) <= 0) break;
                }
                *out_status = status;
                return 1;
            }
            // SIGCHLD for some other reaped descendant (a background server forking off its own
            // children, most likely) — not our shell, so the session isn't actually over yet.
        }

        if (stdin_idx >= 0 && (fds[stdin_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t r = read(STDIN_FILENO, buf, sizeof(buf));
            if (r <= 0) {
                stdin_open = 0; // Kotlin closed the pipe; keep relaying master -> stdout
            } else {
                // Input lost to a dead pty is harmless (session ends via SIGCHLD/EOF).
                if (write_master_all(master, buf, r) < 0 && term_requested) break;
            }
        }

        if (control_idx >= 0 && (fds[control_idx].revents & (POLLIN | POLLHUP | POLLERR))) service_control(master);
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
    // Non-blocking so a large paste can never wedge the relay (see write_master_all). Only this
    // open file description is affected; the child opens the slave by path separately.
    if (fcntl(master, F_SETFL, fcntl(master, F_GETFL, 0) | O_NONBLOCK) != 0) {
        fprintf(stderr, "pty_bridge: fcntl O_NONBLOCK on master failed: %s\n", strerror(errno));
        close(master);
        return 1;
    }
    char slave_path[64];
    if (pty_slave_path(master, slave_path, sizeof(slave_path)) != 0) {
        fprintf(stderr, "pty_bridge: failed to determine pty slave: %s\n", strerror(errno));
        close(master);
        return 1;
    }
    if (rows <= 0) rows = 24;
    if (cols <= 0) cols = 80;
    // Clamp before the unsigned-short truncation in set_winsize: huge values from a
    // corrupted resize message would otherwise wrap to a nonsense window size.
    if (rows > 1000) rows = 1000;
    if (cols > 1000) cols = 1000;
    set_winsize(master, rows, cols);

    int control_fd = open_control_fifo(control_path);
    g_control_fd = control_fd;

    // Installed before fork() specifically so there's no window where the child could exit (an
    // exec failure, a command that's already missing) and deliver SIGCHLD before this process is
    // ready to catch it — a signal delivered with no handler installed is simply lost, not
    // queued, so relay_loop() would then have no way to ever learn that child had already exited.
    int self_pipe[2];
    if (pipe(self_pipe) != 0) {
        fprintf(stderr, "pty_bridge: pipe() failed: %s\n", strerror(errno));
        close(master);
        if (control_fd >= 0) close(control_fd);
        return 1;
    }
    // Unchecked fcntl used to risk a blocking self-pipe: wake_self_pipe()'s write() runs
    // inside signal handlers, where blocking = deadlock. Fail loudly instead.
    if (fcntl(self_pipe[0], F_SETFL, O_NONBLOCK) != 0 || fcntl(self_pipe[1], F_SETFL, O_NONBLOCK) != 0) {
        fprintf(stderr, "pty_bridge: fcntl O_NONBLOCK failed: %s\n", strerror(errno));
        close(master);
        if (control_fd >= 0) close(control_fd);
        close(self_pipe[0]);
        close(self_pipe[1]);
        return 1;
    }
    self_pipe_write_fd = self_pipe[1];
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sigchld_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_NOCLDSTOP; // only "child exited", not every stop/continue of a job in it
    sigaction(SIGCHLD, &sa, NULL);
    // Tab-close path: SIGKILLs the whole guest process group (see handler above).
    struct sigaction sa_term;
    memset(&sa_term, 0, sizeof(sa_term));
    sa_term.sa_handler = sigterm_handler;
    sigemptyset(&sa_term.sa_mask);
    sa_term.sa_flags = 0;
    sigaction(SIGTERM, &sa_term, NULL);

    pid_t child = fork();
    if (child < 0) {
        fprintf(stderr, "pty_bridge: fork failed: %s\n", strerror(errno));
        close(master);
        if (control_fd >= 0) close(control_fd);
        close(self_pipe[0]);
        close(self_pipe[1]);
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
        // An exec with the wrong controlling terminal or stdio is a silently broken
        // session (no input, no output, no signals) — fail loudly instead of exec'ing
        // into it.
        if (ioctl(slave, TIOCSCTTY, 0) != 0) _exit(127);
        if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0) _exit(127);
        if (slave > 2) close(slave);
        close(master);
        if (control_fd >= 0) close(control_fd);
        // Same reasoning as master/control_fd just above: neither the shell nor anything it goes
        // on to spawn has any business holding these open.
        close(self_pipe[0]);
        close(self_pipe[1]);
        // Optional CPU affinity (hex mask in ALPDROID_CPU_MASK, set by the app's "efficiency cores"
        // option): everything the session spawns inherits it, so a busy-looping CLI lands on the
        // phone's low-power cores instead of heating a big one. Best-effort — a failure just leaves
        // the default affinity; the variable is removed so the guest never sees it.
        const char *cpu_mask_env = getenv("ALPDROID_CPU_MASK");
        if (cpu_mask_env != NULL) {
            unsigned long mask = strtoul(cpu_mask_env, NULL, 16);
            if (mask != 0) syscall(__NR_sched_setaffinity, 0, sizeof(mask), &mask);
            unsetenv("ALPDROID_CPU_MASK");
        }
        execvp(cmd_argv[0], cmd_argv);
        _exit(127);
    }

    // Parent: pure relay between the pty master and our own stdio (Kotlin's pipes), plus the
    // resize side channel. Never touches the slave path directly. Both self_pipe ends stay open
    // here (unlike master/control_fd's read/write split above) — this pipe only ever signals
    // *this* process to itself (sigchld_handler writes, relay_loop's poll() reads), so there is
    // no "other side" of it to hand off and no unused end in the parent to close.
    signal(SIGPIPE, SIG_IGN);
    g_child = child; // visible to sigterm_handler: pgid == pid (child did setsid())
    int status = 0;
    int reaped_in_loop = relay_loop(master, child, self_pipe[0], &status);
    if (!reaped_in_loop) {
        // relay_loop ended some other way (master's own EOF, most commonly still — that still
        // happens immediately in the overwhelmingly common case of a program that doesn't spawn
        // anything holding the pty open past its own exit) — child is still ours to reap here,
        // exactly as before this file tracked SIGCHLD at all.
        // If we got here via SIGTERM, the guest group is already SIGKILLed: reap without
        // hanging — a daemon that setsid(2)'d out of the group could otherwise wedge this
        // blocking waitpid() forever.
        if (term_requested) {
            int waited = 0;
            while (waitpid(child, &status, WNOHANG) != child && waited < 50) {
                usleep(20000);
                waited++;
            }
            if (waited >= 50) waitpid(child, &status, WNOHANG);
        } else {
            waitpid(child, &status, 0);
        }
    }
    close(master);
    if (control_fd >= 0) close(control_fd);
    close(self_pipe[0]);
    close(self_pipe[1]);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return 1;
}
