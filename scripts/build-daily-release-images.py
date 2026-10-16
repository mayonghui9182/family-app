"""从已验收候选和固定本机依赖镜像组装镜像，不运行部署或读取环境文件。"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
assembly = json.loads((ROOT / 'docs/daily-candidate-assembly.json').read_text())
CANDIDATE = Path(assembly['directory'])
assert CANDIDATE.resolve().is_relative_to(ROOT / '.volumes/daily-candidates')
BASES = {
    'backend': 'sha256:6d567ea0491aa25433040984b18a980daaaae135ef651461e98702f124d3c26e',
    'frontend': 'sha256:df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2',
    'task': 'sha256:9229372633d3adf83a6ff74601cb4d492b674614f11a4b4d1aa6d72342841661',
    'agent': 'sha256:ea0706c92bd33be25f29192e6b3df08f08f1206b007feb6098af3b640a10e3de',
    'java': 'sha256:97137382c6f0c30427d9b7c44ad8b2d55ac823b0768c171d81a643ed219023c5',
    'nginx': 'sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10',
}


def run(args):
    result = subprocess.run(args, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr)[-2500:])
    return result.stdout.strip()


def main():
    for item in assembly['files']:
        assert hashlib.sha256((CANDIDATE / item['path']).read_bytes()).hexdigest() == item['output_sha256'], item['path']
    for base in BASES.values():
        assert run(['docker', 'image', 'inspect', '--format', '{{.Id}}', base]) == base
    parent = CANDIDATE / 'release-images'
    parent.mkdir(exist_ok=True)
    build = json.loads((ROOT / 'docs/daily-candidate-build-verification.json').read_text())
    jar = CANDIDATE / build['backend_jar']
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == build['backend_jar_sha256']
    configs = {
        'backend': (BASES['backend'], [('app.jar', jar)],
                    'WORKDIR /app\nCOPY app.jar /app/app.jar\n'),
        'frontend': (BASES['frontend'], [('dist', CANDIDATE / 'frontend/dist'), ('nginx-spa.conf', CANDIDATE / 'frontend/nginx-spa.conf')],
                     'COPY dist/ /usr/share/nginx/html/\nCOPY nginx-spa.conf /etc/nginx/conf.d/default.conf\nHEALTHCHECK CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1\n'),
        'tp-task-service': (BASES['task'], [(name, CANDIDATE / 'task-planner/task-service' / name) for name in ('src', 'alembic', 'alembic.ini', 'docker-entrypoint.sh')],
                     'USER root\nRUN rm -rf /app/src /app/alembic\nCOPY src/ /app/src/\nCOPY alembic/ /app/alembic/\nCOPY alembic.ini docker-entrypoint.sh /app/\nRUN chmod 755 /app/docker-entrypoint.sh\nUSER appuser\n'),
        'tp-agent-service': (BASES['agent'], [('src', CANDIDATE / 'task-planner/agent-service/src')],
                     'USER root\nRUN rm -rf /app/src\nCOPY src/ /app/src/\nUSER appuser\n'),
        'daily-oauth': (BASES['java'], [('install', CANDIDATE / 'integrations/daily-oauth/build/install/family-app-daily-oauth')],
                     'WORKDIR /app\nCOPY install/ /app/\nRUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin daily\nUSER daily\nENTRYPOINT ["/app/bin/family-app-daily-oauth"]\n'),
        'daily-mcp': (BASES['task'], [(name, CANDIDATE / 'integrations/daily-mcp' / name) for name in ('daily_mcp.py', 'remote_mcp.py')],
                     'USER root\nRUN rm -rf /app/src /app/alembic /app/alembic.ini /app/docker-entrypoint.sh\nWORKDIR /app\nCOPY daily_mcp.py remote_mcp.py /app/\nUSER appuser\nHEALTHCHECK NONE\nENTRYPOINT ["python", "remote_mcp.py"]\nCMD []\n'),
        'nginx': (BASES['nginx'], [('default.conf', CANDIDATE / 'nginx/default.conf')],
                     'COPY default.conf /etc/nginx/conf.d/default.conf\nEXPOSE 80 443\n'),
    }
    images = {}
    for service, (base, sources, dockerfile) in configs.items():
        context = parent / service
        context.mkdir(exist_ok=True)
        for name, source in sources:
            assert source.resolve().is_relative_to(CANDIDATE)
            target = context / name
            if target.exists():
                if source.is_dir():
                    for existing in target.rglob('*'):
                        if existing.is_file():
                            assert existing.read_bytes() == (source / existing.relative_to(target)).read_bytes()
                else:
                    assert target.read_bytes() == source.read_bytes()
            elif source.is_dir():
                shutil.copytree(source, target, ignore=shutil.ignore_patterns('__pycache__'))
            else:
                shutil.copy2(source, target)
        local_base = 'familyapp-daily-base/' + service + ':20261014'
        run(['docker', 'tag', base, local_base])
        assert run(['docker', 'image', 'inspect', '--format', '{{.Id}}', local_base]) == base
        (context / 'Dockerfile').write_text('FROM ' + local_base + chr(10) + dockerfile)
        files = {p.relative_to(context).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in context.rglob('*') if p.is_file()}
        tag = 'familyapp-daily-candidate/' + service + ':20261014'
        output = run(['docker', 'build', '--network=none', '--pull=false', '-t', tag, str(context)])
        (parent / (service + '-build.log')).write_text(output)
        image = run(['docker', 'image', 'inspect', '--format', '{{.Id}}', tag])
        images[service] = {'tag': tag, 'image_id': image, 'base_image_id': base, 'context_sha256': files}
        (ROOT / 'docs/daily-release-images.json').write_text(json.dumps({'status': '已构建，镜像启动及恢复材料待验收', 'images': images}, ensure_ascii=False, indent=2) + '\n')
        print(service + ' ' + image, flush=True)


if __name__ == '__main__':
    main()
