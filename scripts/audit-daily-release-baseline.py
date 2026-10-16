"""只读比对保留的运行JAR与源码，生成无业务数据的候选基线清单。"""
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / '.volumes/backend-recovery-20261011/app.jar'
EXPECTED = '3d651317a83e2d34ab9ade806e5d4ba5369fda49f161052e5a06435754780e29'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def git_file(path):
    result = subprocess.run(['git', 'show', 'HEAD:' + path], cwd=ROOT, capture_output=True)
    return result.stdout if result.returncode == 0 else None

def main():
    """不连接容器和数据库，不读取环境文件或凭据。"""
    assert digest(JAR.read_bytes()) == EXPECTED, '运行JAR不匹配已记录摘要'
    report = {'jar_sha256': EXPECTED, 'head': subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'migrations': [], 'java_sources': [], 'conversation_consumers': []}
    with zipfile.ZipFile(JAR) as archive:
        names = set(archive.namelist())
        prefix = 'BOOT-INF/classes/db/migration/'
        source = ROOT / 'backend/src/main/resources/db/migration'
        migrations = {p.name for p in source.glob('*.sql')}
        migrations.update(n[len(prefix):] for n in names if n.startswith(prefix) and n.endswith('.sql'))
        for name in sorted(migrations):
            path = 'backend/src/main/resources/db/migration/' + name
            current = (ROOT / path).read_bytes() if (ROOT / path).exists() else None
            running = archive.read(prefix + name) if prefix + name in names else None
            head = git_file(path)
            report['migrations'].append({'path': path, 'running': running is not None,
                'head': head is not None, 'working': current is not None,
                'working_matches_running_bytes': current == running if running is not None else None,
                'working_matches_running_lf': current.replace(b'\r\n', b'\n') == running.replace(b'\r\n', b'\n')
                    if current is not None and running is not None else None,
                'working_sha256': digest(current) if current is not None else None})
        base = ROOT / 'backend/src/main/java'
        for file in sorted((base / 'com/familyapp').rglob('*.java')):
            relative = file.relative_to(ROOT).as_posix()
            raw = file.read_bytes()
            class_name = 'BOOT-INF/classes/' + file.relative_to(base).with_suffix('.class').as_posix()
            head = git_file(relative)
            report['java_sources'].append({'path': relative, 'sha256': digest(raw),
                'class_present_in_running_jar': class_name in names,
                'head_present': head is not None, 'changed_from_head': head != raw,
                'running_class_sha256': digest(archive.read(class_name)) if class_name in names else None})
            if '/wechat/conversation/' not in relative and 'com.familyapp.wechat.conversation' in raw.decode():
                report['conversation_consumers'].append(relative)
    report['limitations'] = ['类存在不证明源码行为相同；需重建或字节码语义比对',
                            '清单为基线审计，尚非最终发布允许清单',
                            '不含前端及Python运行产物的同源证明']
    output = ROOT / 'docs/daily-release-baseline.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'migrations': report['migrations'], 'conversation_consumers': report['conversation_consumers'],
        'java_files': len(report['java_sources']), 'new_classes': sum(not x['class_present_in_running_jar'] for x in report['java_sources'])}, ensure_ascii=False))

if __name__ == '__main__':
    main()
