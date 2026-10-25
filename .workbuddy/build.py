"""Portable, locked build entry. Requires Git and JAVA_HOME; no signing secrets."""
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from work_session import run_android_build

if __name__ == '__main__':
    java = os.environ.get('JAVA_HOME')
    if not java or not Path(java).is_dir():
        raise SystemExit('Set JAVA_HOME to your JDK installation first')
    raise SystemExit(run_android_build(ROOT, sys.argv[1:] or ['assembleDebug'], java))
