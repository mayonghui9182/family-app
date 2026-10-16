"""候选真实 PostgreSQL 测试和应用启动；只创建随机夹具容器及回环监听。"""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CANDIDATE = Path(json.loads((ROOT / 'docs/daily-candidate-assembly.json').read_text())['directory'])
assert CANDIDATE.resolve().is_relative_to(ROOT / '.volumes/daily-candidates')
TOKEN = secrets.token_hex(6)
NAME = 'candidate-pg-' + TOKEN
PASSWORD = secrets.token_hex(24)


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr).replace(PASSWORD, '[夹具凭据]')[-3000:])
    return result.stdout.strip()


def sql(database, query):
    return run(['docker', 'exec', NAME, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                '-U', 'tester', '-d', database, '-Atc', query])


def report(folder):
    result = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in folder.glob('TEST-*.xml'):
        root = ET.parse(file).getroot()
        for key in result:
            result[key] += int(root.attrib.get(key, 0))
    assert result['tests'] > 0 and not any(result[key] for key in ('failures', 'errors', 'skipped')), result
    return result


def main():
    created = False
    app = None
    evidence = {'scope': '候选完整Java测试含真实PG条件用例，以及候选JAR空库启动；虚构数据，无生产访问'}
    try:
        run(['docker', 'run', '-d', '--name', NAME, '--label', 'familyapp.candidate-pg=' + NAME,
             '-p', '127.0.0.1::5432', '-e', 'POSTGRES_USER=tester', '-e', 'POSTGRES_DB=fixture',
             '-e', 'POSTGRES_PASSWORD=' + PASSWORD, 'postgres:16-alpine'])
        created = True
        for _ in range(45):
            ready = subprocess.run(['docker', 'exec', NAME, 'pg_isready', '-U', 'tester'], capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('隔离数据库启动超时')
        port = run(['docker', 'port', NAME, '5432/tcp'])
        assert port.startswith('127.0.0.1:') and '\n' not in port
        dailydb, oauthdb = 'familyapp_daily_' + TOKEN + '_test', 'familyapp_oauth_it_' + TOKEN
        for database in (dailydb, oauthdb, 'startup_fixture'):
            sql('fixture', 'CREATE DATABASE ' + database)
        env = dict(os.environ, FAMILYAPP_DAILY_POSTGRES_URL='jdbc:postgresql://' + port + '/' + dailydb,
                   FAMILYAPP_DAILY_POSTGRES_USER='tester', FAMILYAPP_DAILY_POSTGRES_PASSWORD=PASSWORD,
                   DAILY_OAUTH_POSTGRES_URL='jdbc:postgresql://' + port + '/' + oauthdb,
                   DAILY_OAUTH_POSTGRES_PASSWORD=PASSWORD)
        for label, command in [
            ('backend', ['backend/gradlew', '-p', 'backend', 'test', '--rerun-tasks', '--offline', '--no-daemon']),
            ('oauth', ['backend/gradlew', '-p', 'integrations/daily-oauth', 'test', '--rerun-tasks', '--offline', '--no-daemon'])
        ]:
            with (CANDIDATE / (label + '-postgres.log')).open('w') as log:
                result = subprocess.run(command, cwd=CANDIDATE, env=env, stdout=log, stderr=subprocess.STDOUT)
            assert result.returncode == 0, label + '测试失败，详见候选本地日志'
            folder = CANDIDATE / ('backend' if label == 'backend' else 'integrations/daily-oauth')
            evidence[label] = report(folder / 'build/test-results/test')
            print(label + '真实PG测试通过', flush=True)
        build = json.loads((ROOT / 'docs/daily-candidate-build-verification.json').read_text())
        jar = CANDIDATE / build['backend_jar']
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == build['backend_jar_sha256']
        appenv = dict(os.environ, SPRING_DATASOURCE_URL='jdbc:postgresql://' + port + '/startup_fixture?stringtype=unspecified',
                      SPRING_DATASOURCE_USERNAME='tester', SPRING_DATASOURCE_PASSWORD=PASSWORD,
                      INTERNAL_API_SECRET=secrets.token_hex(32), SERVER_ADDRESS='127.0.0.1', SERVER_PORT='0',
                      DAILY_ENABLED='false', DAILY_REMOTE_ENABLED='false', AI_DECOMPOSE_ENABLED='false',
                      WECHAT_NOTIFY_ENABLED='false', WECHAT_INTAKE_ENABLED='false')
        logfile = CANDIDATE / 'postgres-startup.log'
        with logfile.open('w') as log:
            app = subprocess.Popen(['java', '-jar', str(jar)], cwd=CANDIDATE, env=appenv,
                                   stdout=log, stderr=subprocess.STDOUT)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(90):
            assert app.poll() is None, '候选启动失败，详见本地postgres-startup.log'
            match = re.search(r'Tomcat started on port (\d+)', logfile.read_text())
            if match:
                with opener.open('http://127.0.0.1:' + match[1] + '/actuator/health', timeout=5) as response:
                    assert json.load(response)['status'] == 'UP'
                break
            time.sleep(1)
        else:
            raise RuntimeError('候选启动超时')
        assert sql('startup_fixture', "SELECT max(version::int) FROM public.flyway_schema_history WHERE type='SQL'") == '14'
        assert sql('startup_fixture', "SELECT version FROM daily_app.flyway_schema_history WHERE type='SQL'") == '1'
        assert sql('startup_fixture', "SELECT to_regclass('public.daily_records') IS NULL") == 't'
        evidence['startup'] = {'health': 'UP', 'main_version': '14', 'daily_version': '1', 'public_daily_tables_absent': True}
        evidence['jar_sha256'] = build['backend_jar_sha256']
        print('候选JAR真实PG启动通过：主V14、每日V1、健康UP', flush=True)
    finally:
        if app is not None and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=20)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait()
        if created:
            labels = json.loads(run(['docker', 'inspect', '--format', '{{json .Config.Labels}}', NAME]))
            assert labels.get('familyapp.candidate-pg') == NAME
            run(['docker', 'rm', '-f', '-v', NAME])
    evidence['test_resources_removed'] = True
    (ROOT / 'docs/daily-candidate-postgres-verification.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == '__main__':
    main()
