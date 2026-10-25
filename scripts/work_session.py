"""小任务登记、资源互斥和 Android 构建证据，不读取任何凭据。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from workflow_lock import resource_lock

ROOT = Path(__file__).resolve().parents[1]
MAX_ACTIVE_TASKS = 4  # 用户2026-10-17明确授权：含主会话最多四个AI并行。


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args]).decode('utf-8').strip()


def common_root(root):
    # 同仓库的 worktree 共用登记和设备锁；每棵编译树使用独立构建锁。
    common = Path(git(root, 'rev-parse', '--git-common-dir'))
    if not common.is_absolute():
        common = root / common
    return common.resolve().parent


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + f'.{os.getpid()}.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    os.replace(temporary, path)


def valid_path(value):
    value = value.replace('\\', '/').lower()
    if not value or ':' in value or any(part in ('.', '..', '') for part in value.split('/')):
        raise ValueError('占用路径必须是仓库相对路径，不能有 . / .. 或盘符')
    if any(c in value for c in '{}?'):
        raise ValueError('请逐条登记实际路径；只支持 * 和 **，不支持花括号简写')
    if value in ('*', '**', 'apps/**', 'backend/**'):
        raise ValueError('范围过大，请登记具体模块或文件')
    return value


def overlaps(left, right):
    if left.startswith(right + '/') or right.startswith(left + '/'):
        return True  # 登记目录本身时也保护它的子文件。
    # Nuxt 的 [id].vue 是字面文件名，不把方括号误当 glob 字符集。
    def matches(value, pattern):
        return re.fullmatch(re.escape(pattern).replace(r'\*', '.*'), value) is not None
    if matches(left, right) or matches(right, left):
        return True
    a = left.split('*', 1)[0].rstrip('/')
    b = right.split('*', 1)[0].rstrip('/')
    return ('*' in left or '*' in right) and (a == b or a.startswith(b + '/') or b.startswith(a + '/'))


def source_snapshot(root):
    paths = git(root, 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', 'apps/android').split('\0')
    digest = hashlib.sha256()
    for name in sorted(set(filter(None, paths))):
        path = root / name
        digest.update(name.encode('utf-8'))
        digest.update(b'\0')
        digest.update(hashlib.sha256(path.read_bytes()).digest() if path.is_file() else b'<deleted>')
    return {
        'commit': git(root, 'rev-parse', 'HEAD'),
        'androidTreeSha256': digest.hexdigest(),
        'androidDirty': bool(git(root, 'status', '--porcelain', '--', 'apps/android')),
    }


def run_android_build(root, tasks, java_home):
    if any(t.split(':')[-1] == 'clean' for t in tasks) and len(tasks) > 1:
        raise ValueError('clean 必须单独运行，不能和其他构建任务混在同一次调用')
    shared = common_root(root)
    android = root / 'apps/android'
    with resource_lock(shared, 'android-build:' + str(android.resolve()).lower()):
        start = time.time_ns()
        run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + f'-{os.getpid()}-{start}'
        run_dir = root / '.workbuddy/ai/runtime/builds' / run_id
        run_dir.mkdir(parents=True)
        before = source_snapshot(root)
        env = dict(os.environ, JAVA_HOME=java_home, GRADLE_OPTS='-Dfile.encoding=UTF-8')
        env['PATH'] = java_home + os.sep + 'bin' + os.pathsep + env.get('PATH', '')
        wrapper = android / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        code = 124
        output = ''
        try:
            result = subprocess.run([str(wrapper), '--no-daemon', '--console=plain', '-q', *tasks],
                                    cwd=android, env=env, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=60 * 25)
            code = result.returncode
            output = result.stdout.decode('utf-8', 'replace')
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b'').decode('utf-8', 'replace') + '\n构建超时；请确认 Gradle 子进程已退出后再重试。'
        after = source_snapshot(root)
        consistent = before['androidTreeSha256'] == after['androidTreeSha256']
        evidence = {'schema': 1, 'runId': run_id, 'workspace': str(root.resolve()),
                    'tasks': tasks, 'startedAtNs': start, 'sourceBefore': before,
                    'sourceAfter': after, 'sourceConsistent': consistent,
                    'gradleExit': code, 'artifacts': []}
        if not consistent:
            output += '\n构建期间源码发生变化：拒绝为本次产物绑定源码身份，请在稳定源码上重建。'
        if code == 0 and consistent:
            for apk in sorted((android / 'app/build/outputs/apk').glob('**/*.apk')):
                if apk.stat().st_mtime_ns < start:
                    continue  # 旧 APK 不冒充本次新产物。
                with apk.open('rb') as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                evidence['artifacts'].append({'path': str(apk.resolve()), 'size': apk.stat().st_size,
                                             'sha256': digest})
        final_code = code if code != 0 else (0 if consistent else 4)
        evidence['exit'] = final_code
        text = f'cmd: {" ".join(tasks)}\nexit: {final_code}\ngradle_exit: {code}\nelapsed: {(time.time_ns()-start)/1e9:.1f}s\nrun: {run_id}\n' + '-' * 70 + '\n' + output
        (run_dir / 'output.txt').write_text(text, encoding='utf-8')
        write_json(run_dir / 'receipt.json', evidence)
        (root / '.workbuddy/build_out.txt').write_text(text, encoding='utf-8')
        print(f'exit={final_code}，独立证据：{run_dir / "receipt.json"}')
        return final_code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    begin = commands.add_parser('begin')
    begin.add_argument('id')
    begin.add_argument('--owner', required=True, help='会话唯一名称；每个会话只能登记一件进行中的任务')
    begin.add_argument('--title', required=True)
    begin.add_argument('--outcome', required=True, help='一个可演示的交付结果，不写全面优化')
    begin.add_argument('--paths', nargs='+', required=True)
    finish = commands.add_parser('finish')
    finish.add_argument('id')
    finish.add_argument('--evidence', required=True)
    status = commands.add_parser('status')
    status.add_argument('--active', action='store_true', help='只显示进行中的任务，不重复输出释放历史')
    status.add_argument('--summary', action='store_true', help='显示简短占用清单，不展开整份登记')
    run = commands.add_parser('run')
    run.add_argument('--resource', required=True, help='如 device-8b4766a5；整段走查共用一个锁')
    run.add_argument('command', nargs=argparse.REMAINDER)
    artifact = commands.add_parser('artifact')
    artifact.add_argument('--receipt', type=Path, required=True)
    artifact.add_argument('--apk', type=Path, required=True)
    artifact.add_argument('--apksigner', type=Path, required=True)
    artifact.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    shared = common_root(ROOT)
    if args.action == 'run':
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        if not command:
            raise ValueError('请提供实际执行命令')
        with resource_lock(shared, args.resource):
            return subprocess.call(command, cwd=ROOT)
    if args.action == 'artifact':
        receipt = json.loads(args.receipt.read_text(encoding='utf-8'))
        if receipt['exit'] != 0 or not receipt['sourceConsistent']:
            raise ValueError('构建证据未通过，拒绝绑定 APK')
        with args.apk.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        built = next((item for item in receipt['artifacts'] if item['sha256'] == digest), None)
        if not built:
            raise ValueError('APK 不是这份构建证据记录的产物；不能拿旧包绑定当前提交')
        cert = subprocess.check_output([str(args.apksigner), 'verify', '--print-certs', str(args.apk)]).decode('utf-8', 'replace')
        signers = sorted(set(re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', cert)))
        if not signers:
            raise ValueError('无法核实签名')
        write_json(args.out, {**built, 'path': str(args.apk.resolve()), 'source': receipt['sourceBefore'],
                              'runId': receipt['runId'], 'signerSha256': signers})
        print('产物来源、哈希与签名证据已保存；这不代表设备或线上验收通过。')
        return 0
    registry = shared / '.workbuddy/ai/runtime/tasks.json'
    with resource_lock(shared, 'task-registry'):
        rows = json.loads(registry.read_text(encoding='utf-8')) if registry.exists() else []
        if args.action == 'status':
            selected = [r for r in rows if r['state'] == 'active'] if args.active else rows
            if args.summary:
                active = [r for r in rows if r['state'] == 'active']
                print(f'进行中：{len(active)}/{MAX_ACTIVE_TASKS}；登记历史：{len(rows)}')
                for row in selected:
                    print(f'{row["state"]} | {row["owner"]} | {row["id"]} | {", ".join(row["paths"])}')
            else:
                print(json.dumps(selected, ensure_ascii=False, indent=2))
            return 0
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,47}', args.id):
            raise ValueError('任务ID只用小写字母、数字、短横线，长度2–48')
        if args.action == 'begin':
            if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,47}', args.owner):
                raise ValueError('会话名称只用小写字母、数字、短横线，长度2–48；不要临时换名绕过限额')
            if len(args.paths) > 16 or any(x in args.title + args.outcome for x in ['全面优化', '全部搞完']):
                raise ValueError('任务太大：拆成一个可演示结果、最多16个实际路径')
            paths = [valid_path(p) for p in args.paths]
            for row in rows:
                if row['state'] != 'active':
                    continue
                if row.get('owner') == args.owner or row['id'] == args.id or any(overlaps(a, b) for a in paths for b in row['paths']):
                    raise ValueError(f'与 {row["id"]} 冲突，请等待释放或缩小范围')
            if sum(row['state'] == 'active' for row in rows) >= MAX_ACTIVE_TASKS:
                raise ValueError(f'{MAX_ACTIVE_TASKS}个并发槽位已满，请等待现有任务释放；禁止更换owner绕过限制')
            rows.append({'id': args.id, 'owner': args.owner, 'title': args.title, 'outcome': args.outcome, 'paths': paths,
                         'workspace': str(ROOT.resolve()),
                         'state': 'active', 'registeredAt': datetime.now(timezone.utc).isoformat()})
        else:
            row = next((r for r in rows if r['id'] == args.id and r['state'] == 'active'), None)
            if not row:
                raise ValueError('不存在这个进行中的任务')
            row.update(state='released', evidence=args.evidence, releasedAt=datetime.now(timezone.utc).isoformat())
        write_json(registry, rows)
        print('登记已更新；旧会话仍需先核对 NOW.md，不能因未进新登记簿就抢文件。')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'操作中止：{error}', file=sys.stderr)
        sys.exit(3)
