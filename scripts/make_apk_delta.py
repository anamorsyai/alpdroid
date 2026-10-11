#!/usr/bin/env python3
"""Builds an AlpDroid APK delta ("ADP1") that turns one released APK into the next.

    make_apk_delta.py OLD.apk NEW.apk OUT.adp

The updater on the phone applies it to the APK it already has installed (see ApkDelta.kt), so only what changed is
downloaded; the result is checked against the new APK's SHA-256 before it is installed, and any problem makes the app
fall back to the full download. The old APK is cut into aligned blocks; the new one is scanned with a rolling checksum
for blocks that already exist in the old one, which finds unchanged parts even when they moved.

Format (all integers big-endian):
    "ADP1" | sha256(old APK) 32 | sha256(new APK) 32 | size of new APK u64 | zlib stream of operations
    operations: 'C' offset u64 length u64   copy bytes from the old APK
                'I' length u64 + bytes      insert these bytes
                'E'                         end
"""
import hashlib
import struct
import sys
import zlib

BLOCK = 1024
MOD = 65521


def _weak(buf):
    a, b = 1, 0
    for x in buf:
        a = (a + x) % MOD
        b = (b + a) % MOD
    return a, b


def delta_ops(old, new, block=BLOCK):
    index = {}
    for i in range(0, len(old) - block + 1, block):
        index.setdefault(zlib.adler32(old[i:i + block]), []).append(i)
    ops, lit, n = [], bytearray(), len(new)
    if n < block or not index:
        return [("I", bytes(new))] if n else []
    p = 0
    a, b = _weak(new[0:block])
    while p <= n - block:
        w = (b << 16) | a
        hit = None
        cands = index.get(w)
        if cands:
            chunk = new[p:p + block]
            for o in cands:
                if old[o:o + block] == chunk:
                    hit = o
                    break
        if hit is not None:
            length = block
            while p + length < n and hit + length < len(old) and new[p + length] == old[hit + length]:
                length += 1
            while lit and hit > 0 and lit[-1] == old[hit - 1]:  # grow backwards into the pending insert
                lit.pop()
                hit -= 1
                p -= 1
                length += 1
            if lit:
                ops.append(("I", bytes(lit)))
                lit = bytearray()
            ops.append(("C", hit, length))
            p += length
            if p <= n - block:
                a, b = _weak(new[p:p + block])
        else:
            lit.append(new[p])
            if p + block < n:
                x, y = new[p], new[p + block]
                a = (a - x + y) % MOD
                b = (b - block * x - 1 + a) % MOD
            p += 1
    lit.extend(new[p:])
    if lit:
        ops.append(("I", bytes(lit)))
    return ops


def encode(old, new, ops):
    body = bytearray()
    for op in ops:
        if op[0] == "C":
            body += b"C" + struct.pack(">QQ", op[1], op[2])
        else:
            body += b"I" + struct.pack(">Q", len(op[1])) + op[1]
    body += b"E"
    head = b"ADP1" + hashlib.sha256(old).digest() + hashlib.sha256(new).digest() + struct.pack(">Q", len(new))
    return head + zlib.compress(bytes(body), 9)


def apply(old, patch):
    """Reference applier, used to verify what was just built."""
    assert patch[:4] == b"ADP1"
    new_size = struct.unpack(">Q", patch[68:76])[0]
    data, out, i = zlib.decompress(patch[76:]), bytearray(), 0
    while True:
        t = data[i:i + 1]
        i += 1
        if t == b"C":
            off, ln = struct.unpack(">QQ", data[i:i + 16])
            i += 16
            out += old[off:off + ln]
        elif t == b"I":
            ln = struct.unpack(">Q", data[i:i + 8])[0]
            i += 8
            out += data[i:i + ln]
            i += ln
        else:
            break
    assert len(out) == new_size and hashlib.sha256(out).digest() == patch[36:68], "delta does not rebuild the new APK"
    return bytes(out)


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 2
    old = open(argv[1], "rb").read()
    new = open(argv[2], "rb").read()
    patch = encode(old, new, delta_ops(old, new))
    assert apply(old, patch) == new
    if len(patch) * 10 >= len(new) * 9:
        # A patch about as big as the APK saves nothing, yet the updater prefers a delta whenever one exists: publish none.
        print(f"no delta: the patch ({len(patch)} bytes) is not meaningfully smaller than the APK ({len(new)} bytes)")
        return 1
    open(argv[3], "wb").write(patch)
    print(f"{argv[3]}: {len(patch)} bytes ({100 * len(patch) // max(1, len(new))}% of the {len(new)}-byte APK)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
