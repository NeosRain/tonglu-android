"""会话与构建使用的跨进程互斥锁；退出进程后由操作系统释放。"""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path


@contextmanager
def resource_lock(root: Path, resource: str):
    directory = root / '.workbuddy/ai/runtime/locks'
    directory.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256(resource.encode()).hexdigest()
    path = directory / (key + '.lock')
    with path.open('a+b') as stream:
        stream.seek(0, 2)
        if stream.tell() == 0:
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError(f'资源正在使用：{resource}；请等待释放，不要强抢。') from error
        info = path.with_suffix('.json')
        info.write_text(json.dumps({'pid': os.getpid(), 'resource': resource}, ensure_ascii=False), encoding='utf-8')
        try:
            yield
        finally:
            # 元数据只供诊断；互斥以系统锁为准，不能靠文件是否存在判断。
            info.unlink(missing_ok=True)
            stream.seek(0)
            if os.name == 'nt':
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)
