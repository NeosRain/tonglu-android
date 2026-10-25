"""Restore the official compile-time AAR, with a pinned upstream SHA-256.

Voice models are optional runtime downloads and not needed for compilation.
"""
import argparse
import hashlib
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'apps/android/app/libs/sherpa-onnx.aar'
URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar'
SHA256 = '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
SIZE = 50129134


def valid(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == SHA256


def prepare(check):
    if valid(DEST):
        print('Official sherpa-onnx 1.13.8 AAR verified: ' + SHA256)
        return
    if check:
        raise SystemExit('AAR missing or checksum mismatch; run without --check to restore it')
    DEST.parent.mkdir(parents=True, exist_ok=True)
    temporary = DEST.with_suffix('.aar.part')
    try:
        request = urllib.request.Request(URL, headers={'User-Agent': 'Tonglu-Source-Build/1.0'})
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as output:
            total = 0
            while block := response.read(1024 * 1024):
                total += len(block)
                if total > SIZE:
                    raise ValueError('AAR exceeds pinned size')
                output.write(block)
        if not valid(temporary):
            raise ValueError('Official AAR checksum/size mismatch; refusing dependency')
        with zipfile.ZipFile(temporary) as archive:
            if 'classes.jar' not in archive.namelist() or archive.testzip() is not None:
                raise ValueError('Invalid AAR ZIP')
        temporary.replace(DEST)
        print('Official sherpa-onnx AAR restored and verified')
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only', choices=['aar'], default='aar')
    parser.add_argument('--check', action='store_true')
    prepare(parser.parse_args().check)
