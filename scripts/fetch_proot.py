#!/usr/bin/env python3
"""
Fetches a `proot` build (+ its runtime shared-library dependencies) for each Android ABI this
app supports, and stages them under jniLibs/<abi>/ as lib*.so files.

Why lib*.so: Android's APK installer only extracts files under jniLibs/<abi>/ as real,
independently-executable files at install time (everything else in the APK stays zipped and
mapped read-only, which W^X on API 29+ refuses to execute). Naming a plain executable
"lib<name>.so" is the standard, documented way around that — it doesn't need to actually be a
shared library, just live in that directory with that suffix.

Source: Termux's own package repository (https://packages.termux.dev), which publishes
prebuilt `proot` for Android. This only runs at Gradle-configuration time in an environment
with real network access (Android Studio / CI) — this repo's own sandbox cannot reach it, by
design (see README).

`proot` itself is GPL-2.0 (invoked here as a subprocess, never linked into this app's own
code); its runtime dependency `libtalloc` is LGPL-3.0.
"""
from __future__ import annotations

import argparse
import io
import lzma
import re
import shutil
import sys
import tarfile
import time
import urllib.request
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path

TERMUX_MIRRORS = (
    "https://packages.termux.dev/apt/termux-main",
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://grimler.se/termux/termux-main",
)

# Android ABI -> Termux's own architecture name for that ABI.
ANDROID_ABI_TO_TERMUX_ARCH = {
    "arm64-v8a": "aarch64",
    "armeabi-v7a": "arm",
    "x86_64": "x86_64",
    "x86": "i686",
}

# proot's own runtime deps on Termux builds: talloc (memory pool allocator) and
# android-shmem (POSIX shared memory shim, since Android's bionic lacks real shm_open).
WANTED_PACKAGES = ("proot", "libtalloc", "libandroid-shmem")

USER_AGENT = "alpine-terminal-build/1 (+https://github.com)"


def http_get(url: str, attempts: int = 4) -> bytes:
    last_error: Exception | None = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=120) as response:
                data = response.read()
                if data:
                    return data
                last_error = RuntimeError("empty response body")
        except Exception as exc:  # noqa: BLE001 - retried below regardless of cause
            last_error = exc
        time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"GET {url} failed after {attempts} attempts: {last_error}")


def fetch_from_any_mirror(relative_path: str) -> bytes:
    errors = []
    for mirror in TERMUX_MIRRORS:
        url = f"{mirror.rstrip('/')}/{relative_path.lstrip('/')}"
        try:
            return http_get(url)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{url}: {exc}")
    raise RuntimeError(f"All mirrors failed for {relative_path}:\n" + "\n".join(errors))


@dataclass
class DebRecord:
    name: str
    version: str
    filename: str
    sha256: str


def parse_packages_index(text: str) -> dict[str, DebRecord]:
    """Debian control-file stanza format: blank-line-separated blocks of 'Key: Value' lines."""
    records: dict[str, DebRecord] = {}
    for block in text.split("\n\n"):
        if not block.strip():
            continue
        fields: dict[str, str] = {}
        for line in block.splitlines():
            if ":" not in line or line.startswith(" "):
                continue
            key, _, value = line.partition(":")
            fields[key.strip()] = value.strip()
        name = fields.get("Package")
        if not name:
            continue
        # Keep the highest-versioned stanza per package name; the index lists every version
        # ever published, newest usually last but not guaranteed.
        candidate = DebRecord(
            name=name,
            version=fields.get("Version", ""),
            filename=fields.get("Filename", ""),
            sha256=fields.get("SHA256", ""),
        )
        existing = records.get(name)
        if existing is None or deb_version_key(candidate.version) > deb_version_key(existing.version):
            records[name] = candidate
    return records


def deb_version_key(version: str) -> tuple:
    """Sort key for Debian version strings (epoch:upstream-revision).

    Plain string comparison misorders across digit widths ("9" > "10"), picking a
    stale package as "newest". Split into numeric runs (compared as ints) and
    non-numeric runs (compared lexically), which orders real-world versions right.
    """
    parts = re.split(r"(\d+)", version)
    return tuple(int(p) if p.isdigit() else p for p in parts)


def extract_ar_member(deb_bytes: bytes, member_prefix: str) -> tuple[str, bytes] | None:
    """Minimal Unix `ar` archive reader — just enough to pull one member out of a .deb."""
    magic = b"!<arch>\n"
    if not deb_bytes.startswith(magic):
        raise ValueError("Not an ar archive (missing !<arch> magic)")
    offset = len(magic)
    while offset < len(deb_bytes):
        header = deb_bytes[offset:offset + 60]
        if len(header) < 60:
            break
        # GNU ar keeps a trailing "/" terminator on member names (e.g. "data.tar.xz/").
        name = header[0:16].decode("ascii").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        offset += 60
        data = deb_bytes[offset:offset + size]
        offset += size
        if size % 2 == 1:
            offset += 1  # ar pads members to even length
        if name.startswith(member_prefix):
            return name, data
    return None


def decompress_tar_member(name: str, data: bytes) -> tarfile.TarFile:
    if name.endswith(".gz"):
        return tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")
    if name.endswith(".xz"):
        return tarfile.open(fileobj=io.BytesIO(lzma.decompress(data)), mode="r:")
    raise ValueError(f"Unsupported data.tar compression for member {name!r}")


def extract_termux_prefixed_files(deb_bytes: bytes) -> dict[str, bytes]:
    """Returns {path-relative-to-termux-prefix: file bytes} for every regular file in data.tar."""
    found = extract_ar_member(deb_bytes, "data.tar")
    if found is None:
        raise ValueError("data.tar member not found in .deb")
    member_name, member_data = found
    prefix = "./data/data/com.termux/files/usr/"
    out: dict[str, bytes] = {}
    with decompress_tar_member(member_name, member_data) as tar:
        for entry in tar.getmembers():
            entry_name = entry.name if entry.name.startswith("./") else f"./{entry.name}"
            if not entry_name.startswith(prefix):
                continue
            if not entry.isfile():
                continue
            relative = entry_name[len(prefix):]
            f = tar.extractfile(entry)
            if f is None:
                continue
            out[relative] = f.read()
    return out


def patch_soname_reference(binary: bytes, base_name: str) -> bytes:
    """
    Rewrites any ELF dynamic-string-table entry like "libtalloc.so.2\\0" down to
    "libtalloc.so\\0" (padded with extra NUL bytes to the exact same length), so the dynamic
    linker looks for the plain "libtalloc.so" filename we ship instead of a versioned soname
    we didn't preserve. Safe because ELF string-table entries are NUL-terminated: shortening one
    and padding the freed space with more NUL bytes doesn't shift any other string's offset.
    """
    pattern = re.compile(re.escape(base_name).encode() + rb"\.so(\.[0-9]+)*\x00")

    def _replace(match: re.Match[bytes]) -> bytes:
        original_len = len(match.group(0))
        replacement = f"{base_name}.so".encode() + b"\x00"
        if len(replacement) > original_len:
            return match.group(0)  # shouldn't happen; leave untouched rather than corrupt it
        return replacement + b"\x00" * (original_len - len(replacement))

    return pattern.sub(_replace, binary)


def stage_abi(termux_arch: str, android_abi: str, output_dir: Path) -> None:
    index_path = f"dists/stable/main/binary-{termux_arch}/Packages"
    index_text = fetch_from_any_mirror(index_path).decode("utf-8", "replace")
    records = parse_packages_index(index_text)

    missing = [p for p in WANTED_PACKAGES if p not in records]
    if missing:
        raise RuntimeError(f"[{android_abi}] Termux index missing packages: {missing}")

    abi_dir = output_dir / android_abi
    abi_dir.mkdir(parents=True, exist_ok=True)

    proot_binary: bytes | None = None
    loader_binary: bytes | None = None
    lib_files: dict[str, bytes] = {}  # package_name (e.g. "libtalloc") -> .so bytes

    for package_name in WANTED_PACKAGES:
        record = records[package_name]
        deb_bytes = fetch_from_any_mirror(record.filename)
        if record.sha256:
            actual = sha256(deb_bytes).hexdigest()
            if actual != record.sha256:
                raise RuntimeError(f"[{android_abi}] sha256 mismatch for {record.filename}")
        files = extract_termux_prefixed_files(deb_bytes)
        if package_name == "proot":
            proot_binary = files.get("bin/proot")
            if proot_binary is None:
                raise RuntimeError(f"[{android_abi}] proot package had no bin/proot")
            # proot doesn't embed its loader (the tiny stub it ptraces into a tracee to bootstrap
            # exec) — it's a separate file, pointed to at runtime via PROOT_LOADER.
            loader_binary = files.get("libexec/proot/loader")
            if loader_binary is None:
                raise RuntimeError(f"[{android_abi}] proot package had no libexec/proot/loader")
        else:
            # e.g. "libtalloc" -> lib/libtalloc.so.2.4.3 (versioned soname, hence the suffix match).
            pattern = re.compile(rf"^lib/{re.escape(package_name)}\.so(\.[0-9]+)*$")
            for relative_path, content in files.items():
                if pattern.match(relative_path):
                    lib_files[package_name] = content

    assert proot_binary is not None
    assert loader_binary is not None
    for package_name in lib_files:
        proot_binary = patch_soname_reference(proot_binary, package_name)

    (abi_dir / "libalpineterm_proot.so").write_bytes(proot_binary)
    (abi_dir / "libalpineterm_proot.so").chmod(0o755)
    (abi_dir / "libalpineterm_proot_loader.so").write_bytes(loader_binary)
    (abi_dir / "libalpineterm_proot_loader.so").chmod(0o755)
    for package_name, content in lib_files.items():
        dest = abi_dir / f"{package_name}.so"
        dest.write_bytes(content)
        dest.chmod(0o755)

    sys.stderr.write(
        f"[{android_abi}] staged proot + loader + {sorted(lib_files.keys())} "
        f"({len(proot_binary)} bytes)\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument(
        "--abi",
        action="append",
        dest="abis",
        help="Limit to one ABI (repeatable); default is all supported ABIs.",
    )
    parser.add_argument(
        "--allow-missing",
        action="store_true",
        help="Warn-and-skip an ABI whose fetch fails instead of failing the build. "
        "Default (no flag) fails loudly: a release APK must never silently ship "
        "without proot for an ABI.",
    )
    args = parser.parse_args()

    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    abis = args.abis or list(ANDROID_ABI_TO_TERMUX_ARCH.keys())
    for android_abi in abis:
        termux_arch = ANDROID_ABI_TO_TERMUX_ARCH[android_abi]
        dest_marker = output_dir / android_abi / "libalpineterm_proot.so"
        if dest_marker.exists():
            continue  # cached from a previous configuration pass
        try:
            stage_abi(termux_arch, android_abi, output_dir)
        except Exception as exc:  # noqa: BLE001
            # Don't fail the whole multi-ABI build over one architecture's mirror hiccup —
            # TerminalSession falls back to a plain system shell for any ABI proot is missing.
            sys.stderr.write(f"[{android_abi}] WARNING: proot fetch failed, skipping: {exc}\n")
            shutil.rmtree(output_dir / android_abi, ignore_errors=True)
            if not args.allow_missing:
                raise SystemExit(f"[{android_abi}] failing build: pass --allow-missing to ship without proot for this ABI")


if __name__ == "__main__":
    main()
