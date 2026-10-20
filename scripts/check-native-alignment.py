#!/usr/bin/env python3
"""Reject APK native libraries that require Android's 16 KB compatibility mode.

Checks both ELF LOAD alignment and uncompressed ZIP entry alignment, following
https://developer.android.com/guide/practices/page-sizes . No SDK or NDK required.
"""

import argparse
import struct
import sys
from zipfile import BadZipFile, ZIP_STORED, ZipFile

PAGE_SIZE = 16 * 1024


def check_elf(data):
    if data[:4] != b"\x7fELF" or data[4:6] not in (
        b"\x01\x01", b"\x01\x02", b"\x02\x01", b"\x02\x02"
    ):
        raise ValueError("invalid ELF header")
    endian = "<" if data[5] == 1 else ">"
    is_64 = data[4] == 2
    ph_offset = struct.unpack_from(endian + ("Q" if is_64 else "I"), data, 32 if is_64 else 28)[0]
    ph_size, ph_count = struct.unpack_from(endian + "HH", data, 54 if is_64 else 42)
    ph_format = endian + ("IIQQQQQQ" if is_64 else "IIIIIIII")
    if ph_size < struct.calcsize(ph_format) or ph_offset + ph_size * ph_count > len(data):
        raise ValueError("invalid ELF program headers")
    loads = 0
    for index in range(ph_count):
        header = struct.unpack_from(ph_format, data, ph_offset + index * ph_size)
        if header[0] != 1:  # PT_LOAD
            continue
        loads += 1
        alignment = header[-1]
        offset, address = header[2:4] if is_64 else header[1:3]
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            raise ValueError(f"LOAD segment {index} alignment is {alignment}, needs at least {PAGE_SIZE}")
        if (address - offset) % PAGE_SIZE:
            raise ValueError(f"LOAD segment {index} address and offset are not 16 KB congruent")
    if not loads:
        raise ValueError("ELF has no LOAD segments")


def check_apk(path):
    failures = []
    count = 0
    with ZipFile(path) as apk, open(path, "rb") as raw:
        for entry in apk.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            count += 1
            try:
                check_elf(apk.read(entry))
                if entry.compress_type == ZIP_STORED:
                    raw.seek(entry.header_offset)
                    header = raw.read(30)
                    if header[:4] != b"PK\x03\x04":
                        raise ValueError("invalid ZIP local header")
                    name_size, extra_size = struct.unpack_from("<HH", header, 26)
                    data_offset = entry.header_offset + 30 + name_size + extra_size
                    if data_offset % PAGE_SIZE:
                        raise ValueError(f"uncompressed ZIP data offset {data_offset} is not 16 KB aligned")
                print(f"PASS {entry.filename}")
            except (ValueError, struct.error) as error:
                failures.append(f"{entry.filename}: {error}")
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    print(f"{path}: {count} native libraries, {len(failures)} failures")
    return not failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+", help="APK files to verify")
    args = parser.parse_args()
    passed = True
    for path in args.apk:
        try:
            passed = check_apk(path) and passed
        except (OSError, BadZipFile) as error:
            print(f"FAIL {path}: {error}", file=sys.stderr)
            passed = False
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
