#!/usr/bin/env python3
"""Copy the public signature onto a clean rebuild and demand identical bytes."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def reproduce(signed, unsigned, signer):
    from apksigcopier import do_copy
    with tempfile.TemporaryDirectory(prefix="usuals-reproduction-") as directory:
        output = Path(directory) / "reproduced.apk"
        do_copy(str(signed), str(unsigned), str(output))
        subprocess.run([str(signer), "verify", str(output)], check=True, capture_output=True)
        if signed.read_bytes() != output.read_bytes():
            raise ValueError("The clean rebuild does not reproduce the published APK.")
        return {"reproducible": True, "signedSha256": hashlib.sha256(output.read_bytes()).hexdigest(),
                "unsignedSha256": hashlib.sha256(unsigned.read_bytes()).hexdigest()}


if __name__ == "__main__":
    try:
        print(json.dumps(reproduce(*map(Path, sys.argv[1:]))))
    except subprocess.CalledProcessError as error:
        print((error.stderr or b"").decode(errors="replace"), file=sys.stderr)
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
