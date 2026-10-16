"""用虚构 DNS 和一次性证书验证可选路由；不启动或修改现有网关。"""

import subprocess
import tempfile
from pathlib import Path


def main():
    root = Path(__file__).resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix='daily-nginx-', dir=root / 'tmp_probe') as temp:
        directory = Path(temp)
        cert, key = directory / 'cert.pem', directory / 'key.pem'
        result = subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
            '-subj', '/CN=localhost', '-keyout', str(key), '-out', str(cert)], capture_output=True, check=False)
        if result.returncode:
            raise RuntimeError('一次性证书创建失败')
        for optional in (False, True):
            command = ['docker', 'run', '--rm', '--network', 'none']
            for host in ('backend', 'frontend', 'wechat-service', 'tp-task-service', 'tp-agent-service', 'daily-mcp', 'daily-oauth'):
                command.extend(['--add-host', host + ':127.0.0.1'])
            command.extend(['-v', str(root / 'nginx/default.conf') + ':/etc/nginx/conf.d/default.conf:ro',
                '-v', str(root / 'nginx/https.conf') + ':/etc/nginx/conf.d/https.conf:ro',
                '-v', str(cert) + ':/etc/nginx/tls/fullchain.pem:ro', '-v', str(key) + ':/etc/nginx/tls/privkey.pem:ro'])
            if optional:
                command.extend(['-v', str(root / 'nginx/daily-oauth-routes.conf') + ':/etc/nginx/daily-optional/routes.conf:ro'])
            command.extend(['--entrypoint', 'nginx', 'family-app-nginx:latest', '-t'])
            tested = subprocess.run(command, capture_output=True, text=True, check=False)
            if tested.returncode:
                raise RuntimeError('隔离 Nginx 配置校验失败：' + tested.stderr)
        print('PASS: HTTP/HTTPS 基础与可选 OAuth 路由 nginx -t；仅一次性测试证书。')


if __name__ == '__main__':
    main()
