#!/usr/bin/env python3
"""Integration tests for the native pty_bridge helper (app/src/main/cpp/pty_bridge.c).

Builds it with the host C compiler and drives it through pipes and its control FIFO, the same way the
app does. Covers the failure modes that freeze a terminal session: a program that stops reading its
input, a guest that exits while input is still pending, and interrupting a wedged program.

Run:  python3 scripts/test_pty_bridge.py
"""
import os
import subprocess
import sys
import tempfile
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/cpp/pty_bridge.c")
TMP = tempfile.mkdtemp(prefix="pty-bridge-test-")
BIN = os.path.join(TMP, "pty_bridge")


def build():
    subprocess.run(["cc", "-Wall", "-Wextra", "-O2", "-o", BIN, SRC], check=True)


def start(cmd, name):
    fifo = os.path.join(TMP, name + ".fifo")
    os.mkfifo(fifo)
    p = subprocess.Popen([BIN, fifo, "24", "80", "--"] + cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    out = bytearray()
    threading.Thread(target=lambda: [out.extend(c) for c in iter(lambda: p.stdout.read1(65536), b"")], daemon=True).start()
    time.sleep(0.4)
    return p, os.open(fifo, os.O_WRONLY), out


def control(w, line):
    os.write(w, (line + "\n").encode())


def flood(p, chunks=400):
    def run():
        try:
            for _ in range(chunks):
                p.stdin.write(b"x" * 4096)
                p.stdin.flush()
        except Exception:
            pass
    t = threading.Thread(target=run, daemon=True)
    t.start()
    return t


def wait(p, seconds):
    try:
        return p.wait(seconds)
    except subprocess.TimeoutExpired:
        p.kill()
        return None


failures = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (" - " + detail if detail and not ok else ""))
    if not ok:
        failures.append(name)


def main():
    build()

    p, w, _ = start(["sh", "-c", "sleep 30"], "int")
    control(w, "INT")
    check("INT stops a running program", wait(p, 5) is not None)

    p, w, _ = start(["sh", "-c", "trap '' INT; sleep 30"], "kill")
    control(w, "INT")
    time.sleep(0.8)
    check("a program that ignores INT survives it", p.poll() is None)
    control(w, "KILL")
    check("KILL stops it anyway", wait(p, 5) is not None)

    # Raw-mode program that never reads: the pty input queue fills and the bridge's write blocks.
    p, w, _ = start(["sh", "-c", "stty raw -echo; exec sleep 30"], "stuck")
    t = flood(p)
    time.sleep(2)
    check("input to a program that stopped reading blocks (setup)", t.is_alive())
    control(w, "INT")
    check("INT still works while input is blocked, and the bridge exits without spinning", wait(p, 6) is not None)

    # The guest exits while input is pending: the bridge must follow, not spin forever.
    p, _, _ = start(["sh", "-c", "stty raw -echo; sleep 1; exit 3"], "exits")
    flood(p)
    check("bridge exits with the guest's status when it dies mid-flood", wait(p, 8) == 3)

    # Same, but the guest is killed from outside while the bridge is blocked writing input to it. The
    # old code answered the resulting POLLHUP/EAGAIN by looping at 100% CPU forever.
    p, _, _ = start(["sh", "-c", "stty raw -echo; exec sleep 31.5"], "killed")
    flood(p)
    time.sleep(1.5)
    subprocess.run(["pkill", "-9", "-x", "-f", "^sleep 31.5$"])
    check("bridge exits when the guest is killed while input is pending", wait(p, 6) is not None)

    p, w, out = start(["sh", "-c", "stty size; sleep 1"], "resize")
    control(w, "30 100")
    wait(p, 4)
    check("resize and normal output still work", b"24 80" in bytes(out))

    # Large input to a program that does read it (cat echoes it back): no deadlock.
    p, w, out = start(["cat"], "cat")
    data = b"y" * 100 + b"\n"
    p.stdin.write(data * 5000)
    p.stdin.flush()
    time.sleep(1.5)
    p.stdin.write(b"\x04\x04")
    p.stdin.flush()
    check("a 500KB paste into cat completes", wait(p, 10) is not None and len(out) >= 500_000)

    print()
    print("%d failure(s)" % len(failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
