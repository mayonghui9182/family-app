"""独立随机 PostgreSQL 验证 OAuth 流程；不连接已有业务库。"""

import json
import os
import secrets
import subprocess
import time
from pathlib import Path


def main():
    run_id = secrets.token_hex(6)
    name = 'familyapp-oauth-test-' + run_id
    database = 'familyapp_oauth_it_' + run_id
    password = secrets.token_urlsafe(24)
    try:
        created = subprocess.run(['docker', 'run', '-d', '--name', name, '--label', 'familyapp.test.run=' + name,
            '-p', '127.0.0.1::5432', '-e', 'POSTGRES_USER=tester', '-e', 'POSTGRES_PASSWORD=' + password,
            '-e', 'POSTGRES_DB=' + database, 'postgres:16-alpine'], capture_output=True, check=False)
        if created.returncode:
            raise RuntimeError('隔离测试库创建失败')
        for _ in range(30):
            ready = subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'tester'], capture_output=True, check=False)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('隔离测试库未就绪')
        result = subprocess.run(['docker', 'inspect', '--format', '{{json .NetworkSettings.Ports}}', name],
            capture_output=True, text=True, check=True)
        port = json.loads(result.stdout)['5432/tcp'][0]['HostPort']
        env = os.environ | {'DAILY_OAUTH_POSTGRES_URL': f'jdbc:postgresql://127.0.0.1:{port}/{database}',
                           'DAILY_OAUTH_POSTGRES_PASSWORD': password}
        root = Path(__file__).resolve().parent.parent
        test = subprocess.run(['backend/gradlew', '-p', 'integrations/daily-oauth', 'test', '--tests',
            'com.familyapp.dailyoauth.OAuthPostgresTest', '--no-daemon'], cwd=root, env=env, check=False)
        if test.returncode:
            raise RuntimeError('OAuth PostgreSQL 验证失败')
    finally:
        labels = subprocess.run(['docker', 'inspect', '--format', '{{json .Config.Labels}}', name],
            capture_output=True, text=True, check=False)
        if labels.returncode == 0 and json.loads(labels.stdout).get('familyapp.test.run') == name:
            subprocess.run(['docker', 'rm', '-f', '-v', name], stdout=subprocess.DEVNULL, check=False)


if __name__ == '__main__':
    main()
