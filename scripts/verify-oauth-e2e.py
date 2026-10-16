"""独立网络中的真实 OAuth、PostgreSQL、MCP 与浏览器表单协议验收。"""

import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import (
    HTTPCookieProcessor,
    HTTPRedirectHandler,
    Request,
    build_opener,
)

ROOT = Path(__file__).resolve().parent.parent
FIXTURE_ROOT = ROOT
if '--candidate' in sys.argv:
    ROOT = Path(json.loads((ROOT / 'docs/daily-candidate-assembly.json').read_text())['directory'])
    assert ROOT.resolve().is_relative_to(FIXTURE_ROOT / '.volumes/daily-candidates')
RESOURCE = 'https://app.example.test/mcp/daily'
ISSUER = 'https://app.example.test/daily-oauth'


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, text=True, check=False, **kwargs)
    if result.returncode:
        raise RuntimeError('隔离测试命令失败：' + args[0])
    return result.stdout.strip()


def request(opener, url, data=None, headers=None):
    req = Request(url, data=data, headers=headers or {})
    try:
        response = opener.open(req, timeout=10)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode()


def hidden(body, name):
    result = re.search(r'name="' + re.escape(name) + r'" value="([^"]*)"', body)
    if not result:
        raise RuntimeError('表单字段缺失：' + name)
    return html.unescape(result[1])


def authorize(base, user, client_secret):
    browser = build_opener(NoRedirect(), HTTPCookieProcessor(http.cookiejar.CookieJar()))
    status, _, page = request(browser, base + '/login')
    assert status == 200 and 'font:20px' in page
    data = urlencode({'username': f'user{user}@example.test', 'password': 'fixture-password-123',
                      '_csrf': hidden(page, '_csrf')}).encode()
    status, _, _ = request(browser, base + '/login', data)
    assert status == 302
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    params = {'response_type': 'code', 'client_id': 'chatgpt-fixture', 'redirect_uri': 'https://client.example.test/callback',
              'scope': 'daily:read', 'state': secrets.token_urlsafe(24), 'resource': RESOURCE,
              'code_challenge': challenge, 'code_challenge_method': 'S256'}
    status, headers, _ = request(browser, base + '/oauth2/authorize?' + urlencode(params))
    assert status == 302
    location = urlsplit(headers['Location'])
    assert location.scheme == 'https' and location.hostname == 'app.example.test'
    status, _, page = request(browser, base + '/consent?' + location.query)
    assert status == 200 and '私人工作事项' in page
    data = urlencode({'client_id': hidden(page, 'client_id'), 'state': hidden(page, 'state'),
                      '_csrf': hidden(page, '_csrf'), 'scope': 'daily:read'}).encode()
    status, headers, _ = request(browser, base + '/oauth2/authorize', data)
    assert status == 302 and headers['Location'].startswith('https://client.example.test/callback?')
    code = parse_qs(urlsplit(headers['Location']).query)['code'][0]
    auth = base64.b64encode(('chatgpt-fixture:' + client_secret).encode()).decode()
    data = urlencode({'grant_type': 'authorization_code', 'code': code, 'code_verifier': verifier,
                      'redirect_uri': params['redirect_uri'], 'resource': RESOURCE}).encode()
    status, _, body = request(browser, base + '/oauth2/token', data, {'Authorization': 'Basic ' + auth})
    assert status == 200
    return browser, json.loads(body)['access_token']


def mcp(base, token, user):
    opener = build_opener(NoRedirect())
    payload = {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
               'params': {'name': 'read_daily_plan', 'arguments': {'day': '2026-10-10'}}}
    status, _, body = request(opener, base + '/mcp/daily', json.dumps(payload).encode(),
        {'Host': 'app.example.test', 'Authorization': 'Bearer ' + token,
         'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'})
    if user is None:
        assert status == 401
        return
    assert status == 200, f'MCP 查询失败：HTTP {status}，{body}'
    result = json.loads(body)['result']
    assert result['isError'] is False
    projected = json.loads(result['content'][0]['text'])
    assert projected['content']['items'] == [{'title': f'用户{user}模拟计划'}]
    assert 'feedback' not in projected and 'private:' not in body and 'familyId' not in body


def main():
    prefix = 'familyapp-oauth-e2e-' + secrets.token_hex(6)
    password, client_secret, reader_secret, internal, restricted = [secrets.token_urlsafe(32) for _ in range(5)]
    names = []
    try:
        run(['backend/gradlew', '-p', 'integrations/daily-oauth', 'testClasses', 'installDist', '--no-daemon'], cwd=ROOT)
        run(['docker', 'network', 'create', '--label', 'familyapp.test.run=' + prefix, prefix])
        pg = prefix + '-pg'
        names.append(pg)
        database = 'familyapp_oauth_it_' + prefix.rsplit('-', 1)[1]
        run(['docker', 'run', '-d', '--name', pg, '--network', prefix, '--label', 'familyapp.test.run=' + prefix,
             '-e', 'POSTGRES_USER=tester', '-e', 'POSTGRES_PASSWORD=' + password, '-e', 'POSTGRES_DB=' + database,
             'postgres:16-alpine'])
        for _ in range(30):
            if subprocess.run(['docker', 'exec', pg, 'pg_isready', '-U', 'tester'], capture_output=True, check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('隔离 PostgreSQL 未就绪')
        jdbc = f'jdbc:postgresql://{pg}:5432/{database}'
        java_env = {'DAILY_OAUTH_ENABLED': 'true', 'SERVER_ADDRESS': '0.0.0.0',
            'SERVER_SERVLET_SESSION_COOKIE_SECURE': 'false', 'DAILY_ISSUER': ISSUER, 'DAILY_RESOURCE': RESOURCE,
            'DAILY_OAUTH_DATABASE_URL': jdbc, 'DAILY_OAUTH_DATABASE_USER': 'tester', 'DAILY_OAUTH_DATABASE_PASSWORD': password,
            'DAILY_OAUTH_RESTRICTED_PASSWORD': restricted,
            'DAILY_OAUTH_CHATGPT_CLIENT_ID': 'chatgpt-fixture', 'DAILY_OAUTH_CHATGPT_CLIENT_SECRET': client_secret,
            'DAILY_OAUTH_CHATGPT_REDIRECT_URI': 'https://client.example.test/callback',
            'DAILY_OAUTH_READER_ID': 'reader-fixture', 'DAILY_OAUTH_READER_SECRET': reader_secret}
        env_args = [part for key, value in java_env.items() for part in ('-e', key + '=' + value)]
        install = ROOT / 'integrations/daily-oauth/build/install/family-app-daily-oauth'
        seed = ROOT / 'integrations/daily-oauth/build/classes/java/test'
        run(['docker', 'run', '--rm', '--network', prefix, *env_args,
             '-v', str(install) + ':/app:ro', '-v', str(seed) + ':/fixture/classes:ro',
             'eclipse-temurin:17-jre-jammy', 'java', '-cp', '/app/lib/*:/fixture/classes', 'com.familyapp.dailyoauth.FixtureSeed'])
        java_env.update({'DAILY_OAUTH_DATABASE_URL': jdbc + '?currentSchema=daily_oauth',
                         'DAILY_OAUTH_DATABASE_USER': 'oauth_fixture', 'DAILY_OAUTH_DATABASE_PASSWORD': restricted,
                         'SPRING_FLYWAY_SCHEMAS': 'daily_oauth', 'SPRING_FLYWAY_CREATE_SCHEMAS': 'false'})
        del java_env['DAILY_OAUTH_RESTRICTED_PASSWORD']
        env_args = [part for key, value in java_env.items() for part in ('-e', key + '=' + value)]
        oauth = prefix + '-oauth'
        names.append(oauth)
        run(['docker', 'run', '-d', '--name', oauth, '--network', prefix, '--label', 'familyapp.test.run=' + prefix,
             '-p', '127.0.0.1::9010', *env_args, '-v', str(install) + ':/app:ro',
             'eclipse-temurin:17-jre-jammy', '/app/bin/family-app-daily-oauth'])
        backend = prefix + '-backend'
        names.append(backend)
        run(['docker', 'run', '-d', '--name', backend, '--network', prefix, '--label', 'familyapp.test.run=' + prefix,
             '-e', 'TEST_INTERNAL_SECRET=' + internal, '-v', str(FIXTURE_ROOT / 'scripts/oauth-e2e-backend-fixture.py') + ':/fixture.py:ro',
             '--entrypoint', 'python', 'family-app-tp-task-service:latest', '/fixture.py'])
        remote = prefix + '-mcp'
        names.append(remote)
        remote_env = {'DAILY_REMOTE_ENABLED': 'true', 'DAILY_REMOTE_HOST': '0.0.0.0', 'DAILY_OAUTH_ISSUER': ISSUER,
            'DAILY_OAUTH_RESOURCE': RESOURCE, 'DAILY_OAUTH_INTROSPECTION': f'http://{oauth}:9010/daily-oauth/oauth2/introspect',
            'DAILY_OAUTH_CLIENT_ID': 'reader-fixture', 'DAILY_OAUTH_CLIENT_SECRET': reader_secret,
            'DAILY_REMOTE_BACKEND': f'http://{backend}:18088', 'INTERNAL_API_SECRET': internal, 'PYTHONDONTWRITEBYTECODE': '1'}
        remote_args = [part for key, value in remote_env.items() for part in ('-e', key + '=' + value)]
        run(['docker', 'run', '-d', '--name', remote, '--network', prefix, '--label', 'familyapp.test.run=' + prefix,
             '-p', '127.0.0.1::9011', *remote_args, '-v', str(ROOT / 'integrations/daily-mcp') + ':/verify:ro',
             '-w', '/verify', '--entrypoint', 'python', 'family-app-tp-task-service:latest', 'remote_mcp.py'])

        def port(name, internal_port):
            info = json.loads(run(['docker', 'inspect', '--format', '{{json .NetworkSettings.Ports}}', name]))
            if not info.get(str(internal_port) + '/tcp'):
                print(run(['docker', 'logs', '--tail', '40', name]))
                raise RuntimeError('隔离服务端口尚未绑定')
            return info[str(internal_port) + '/tcp'][0]['HostPort']

        oauth_base = 'http://127.0.0.1:' + port(oauth, 9010) + '/daily-oauth'
        mcp_base = 'http://127.0.0.1:' + port(remote, 9011)

        def ready():
            for _ in range(40):
                try:
                    status, _, _ = request(build_opener(NoRedirect()), oauth_base + '/login')
                    if status == 200:
                        return
                except (URLError, OSError):
                    pass
                time.sleep(1)
            raise RuntimeError('授权服务未就绪')

        ready()
        status, _, metadata = request(build_opener(NoRedirect()), oauth_base + '/.well-known/oauth-authorization-server')
        assert status == 200
        assert json.loads(metadata)['issuer'] == ISSUER
        assert json.loads(metadata)['authorization_endpoint'] == ISSUER + '/oauth2/authorize'
        assert 'S256' in json.loads(metadata)['code_challenge_methods_supported']
        status, _, metadata = request(build_opener(NoRedirect()),
            mcp_base + '/.well-known/oauth-protected-resource/mcp/daily', headers={'Host': 'app.example.test'})
        assert status == 200 and json.loads(metadata)['resource'] == RESOURCE
        first, first_token = authorize(oauth_base, 1, client_secret)
        _, second_token = authorize(oauth_base, 2, client_secret)
        mcp(mcp_base, first_token, 1)
        mcp(mcp_base, second_token, 2)
        status, _, page = request(first, oauth_base + '/authorizations')
        assert status == 200
        data = urlencode({'id': hidden(page, 'id'), '_csrf': hidden(page, '_csrf')}).encode()
        status, _, _ = request(first, oauth_base + '/authorizations/revoke', data)
        assert status == 302
        mcp(mcp_base, first_token, None)
        mcp(mcp_base, second_token, 2)
        run(['docker', 'restart', oauth])
        oauth_base = 'http://127.0.0.1:' + port(oauth, 9010) + '/daily-oauth'
        ready()
        mcp(mcp_base, second_token, 2)
        print('PASS: 两用户浏览器表单授权 → 真实内省/MCP → 个人撤销 → 重启后持久化；独立 Schema 与只读用户权限；后端数据为模拟。', flush=True)
        preview = min(60, max(0, int(os.environ.get('DAILY_OAUTH_PREVIEW_SECONDS', '0'))))
        if preview:
            print('仅模拟账号临时预览：' + oauth_base + '/login', flush=True)
            time.sleep(preview)
    finally:
        for name in reversed(names):
            labels = subprocess.run(['docker', 'inspect', '--format', '{{json .Config.Labels}}', name],
                capture_output=True, text=True, check=False)
            if labels.returncode == 0 and json.loads(labels.stdout).get('familyapp.test.run') == prefix:
                subprocess.run(['docker', 'rm', '-f', '-v', name], stdout=subprocess.DEVNULL, check=False)
        labels = subprocess.run(['docker', 'network', 'inspect', '--format', '{{json .Labels}}', prefix],
            capture_output=True, text=True, check=False)
        if labels.returncode == 0 and json.loads(labels.stdout).get('familyapp.test.run') == prefix:
            subprocess.run(['docker', 'network', 'rm', prefix], stdout=subprocess.DEVNULL, check=False)


if __name__ == '__main__':
    main()
