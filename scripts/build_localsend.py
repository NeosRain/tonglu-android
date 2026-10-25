"""Build the complete LocalSend JNI bridge from source with Rust + Android NDK.

No machine-specific D: paths, prebuilt private .so files or signing credentials.
"""
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def build():
    cargo = shutil.which('cargo')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    explicit_ndk = os.environ.get('ANDROID_NDK_HOME')
    if not cargo or not (sdk or explicit_ndk):
        raise SystemExit('Install Rust/Cargo and set ANDROID_HOME or ANDROID_NDK_HOME')
    ndk = Path(explicit_ndk) if explicit_ndk else Path(sdk) / 'ndk/29.0.14206865'
    hosts = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}
    tools = ndk / 'toolchains/llvm/prebuilt' / hosts[platform.system()] / 'bin'
    if not tools.is_dir():
        raise SystemExit('Android NDK toolchain not found: ' + str(tools))
    env = dict(os.environ)
    env['CARGO_TARGET_DIR'] = str(ROOT / 'work/localsend-target')
    env['PATH'] = str(tools) + os.pathsep + env.get('PATH', '')
    suffix = '.cmd' if os.name == 'nt' else ''
    for abi, target in [('arm64-v8a', 'aarch64-linux-android'), ('x86_64', 'x86_64-linux-android')]:
        linker = tools / (target + '24-clang' + suffix)
        key = target.replace('-', '_')
        env['CARGO_TARGET_' + key.upper() + '_LINKER'] = str(linker)
        env['CC_' + key] = str(linker)
        env['AR_' + key] = str(tools / ('llvm-ar.exe' if os.name == 'nt' else 'llvm-ar'))
        command = [cargo, 'build', '--release', '--locked', '--manifest-path',
                   str(ROOT / 'apps/android/native/localsend/Cargo.toml'), '--target', target, '-j', '2']
        if os.environ.get('CARGO_NET_OFFLINE') == 'true':
            command.append('--offline')
        subprocess.run(command, cwd=ROOT, env=env, check=True)
        output = ROOT / 'apps/android/app/build/generated/localsend' / abi
        output.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(Path(env['CARGO_TARGET_DIR']) / target / 'release/libcampus_localsend.so', output / 'libcampus_localsend.so')


if __name__ == '__main__':
    build()
