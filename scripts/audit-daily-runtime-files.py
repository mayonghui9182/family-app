"""只读取运行容器的程序目录并比较摘要，不读取环境、日志或数据库。"""
import hashlib
import io
import json
import subprocess
import tarfile
import zipfile
import tempfile
import re
import difflib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def sha(data):
    return hashlib.sha256(data).hexdigest()

def command(*args):
    result = subprocess.run(args, capture_output=True)
    if result.returncode:
        raise RuntimeError('程序目录核对失败：' + ' '.join(args[:3]))
    return result.stdout

def files(container, path, local, extensions):
    """只在内存解读程序归档，不提取容器文件到磁盘。"""
    data = command('docker', 'cp', container + ':' + path, '-')
    output = []
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            if not member.isfile() or Path(member.name).suffix not in extensions:
                continue
            parts = Path(member.name).parts[1:]
            if not parts or '..' in parts or '__pycache__' in parts:
                continue
            relative = Path(*parts)
            deployed = archive.extractfile(member).read()
            current = ROOT / local / relative
            output.append({'path': (Path(local) / relative).as_posix(), 'running_sha256': sha(deployed),
                           'working_sha256': sha(current.read_bytes()) if current.is_file() else None,
                           'equal': current.is_file() and current.read_bytes() == deployed})
    return output

def main():
    report = {'scope': '仅程序文件摘要；前端dist比较不是源码同源证明', 'containers': {}, 'files': {}}
    for container, path, local, extensions in [
        ('familyapp-tp-task-service', '/app/src', 'task-planner/task-service/src', {'.py'}),
        ('familyapp-tp-agent-service', '/app/src', 'task-planner/agent-service/src', {'.py'}),
        ('familyapp-frontend', '/usr/share/nginx/html', 'frontend/dist', {'.html', '.js', '.css'}),
    ]:
        report['containers'][container] = command('docker', 'inspect', '--format', '{{.Image}}', container).decode().strip()
        report['files'][container] = files(container, path, local, extensions)
    jar = ROOT / '.volumes/backend-recovery-20261011/app.jar'
    assert sha(jar.read_bytes()) == '3d651317a83e2d34ab9ade806e5d4ba5369fda49f161052e5a06435754780e29'
    classes = []
    with zipfile.ZipFile(jar) as archive:
        for name in sorted(archive.namelist()):
            if not name.startswith('BOOT-INF/classes/com/familyapp/') or not name.endswith('.class'):
                continue
            relative = name.removeprefix('BOOT-INF/classes/')
            current = ROOT / 'backend/build/classes/java/main' / relative
            deployed = archive.read(name)
            classes.append({'class': relative, 'equal': current.is_file() and current.read_bytes() == deployed,
                            'working_present': current.is_file(), 'running_sha256': sha(deployed),
                            'working_sha256': sha(current.read_bytes()) if current.is_file() else None})
    report['backend_classes'] = classes
    differences = []
    with tempfile.TemporaryDirectory(prefix='family-class-audit-') as directory, zipfile.ZipFile(jar) as archive:
        for item in classes:
            if item['equal']:
                continue
            relative = item['class']
            extracted = Path(directory) / relative
            extracted.parent.mkdir(parents=True, exist_ok=True)
            extracted.write_bytes(archive.read('BOOT-INF/classes/' + relative))
            class_name = relative[:-6].replace('/', '.')
            old = command('javap', '-p', '-c', '-classpath', directory, class_name).decode()
            new = command('javap', '-p', '-c', '-classpath', str(ROOT / 'backend/build/classes/java/main'), class_name).decode()
            # 常量池编号变化本身不代表逻辑变化，保留指令、偏移及解析注释。
            old = re.sub(r'#\d+', '#CP', old)
            new = re.sub(r'#\d+', '#CP', new)
            differences.extend(difflib.unified_diff(old.splitlines(), new.splitlines(),
                fromfile='running/' + relative, tofile='working/' + relative, lineterm=''))
    (ROOT / 'docs/daily-backend-bytecode.diff').write_text('\n'.join(differences) + '\n')
    (ROOT / 'docs/daily-runtime-file-audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'files': {key: {'total': len(value), 'equal': sum(x['equal'] for x in value),
                                  'different': [x['path'] for x in value if not x['equal']]}
                               for key, value in report['files'].items()},
                      'backend': {'total': len(classes), 'equal': sum(x['equal'] for x in classes),
                                  'different': [x['class'] for x in classes if not x['equal']]}}, ensure_ascii=False))

if __name__ == '__main__':
    main()
