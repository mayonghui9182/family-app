"""OAuth 平台桩和真实 SDK ASGI 验证，无外网请求。"""

import json
import time
import unittest

import httpx
from remote_mcp import IntrospectionVerifier, RemoteConfig, create_remote_server


class RemoteTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.cfg = RemoteConfig('https://auth.example.test/daily-oauth', 'https://app.example.test/mcp/daily',
            'http://oauth-internal/introspect', 'resource-client', 'fake-secret',
            'http://backend-internal', 'fake-internal')
        self.body = {'active': True, 'sub': '1', 'client_id': 'chatgpt-test', 'scope': 'daily:read',
                     'aud': self.cfg.resource, 'iss': self.cfg.issuer, 'exp': int(time.time()) + 600}
        self.paths = []

    def handler(self, request):
        self.paths.append(request.url.path)
        if request.url.path == '/introspect':
            body = self.body.copy()
            if b'token=second-user' in request.content:
                body['sub'] = '2'
            return httpx.Response(200, json=body)
        self.assertEqual(request.headers.get('x-internal-secret'), 'fake-internal')
        self.assertNotIn('authorization', request.headers)
        return httpx.Response(200, json={'code': 0, 'data': {'content': {'items': [
            {'title': request.url.path.split('/')[4], 'key': 'private-id'}]}}})

    async def test_verifier_rejects_invalid_identity_and_revocation(self):
        verifier = IntrospectionVerifier(self.cfg, httpx.MockTransport(self.handler))
        self.assertEqual((await verifier.verify_token('test')).subject, '1')
        good = self.body.copy()
        for changes in ({'active': False}, {'active': 'true'}, {'sub': 'default'}, {'sub': '0'},
                        {'iss': 'https://other.example.test'}, {'scope': 'tasks:write'},
                        {'aud': 'https://other.example.test/mcp'}, {'exp': 1}, {'exp': True},
                        {'client_id': ''}, {'sub': '9223372036854775808'}):
            self.body = good | changes
            self.assertIsNone(await verifier.verify_token('test'), changes)
        self.body = good | {'aud': [self.cfg.resource]}
        self.assertIsNotNone(await verifier.verify_token('test'))
        failed = IntrospectionVerifier(self.cfg, httpx.MockTransport(lambda request: httpx.Response(302,
            headers={'location': 'https://untrusted.example.test'})))
        self.assertIsNone(await failed.verify_token('test'))

    async def test_metadata_challenge_tools_and_two_users(self):
        server = create_remote_server(self.cfg, httpx.MockTransport(self.handler))
        app = server.streamable_http_app()
        async with app.router.lifespan_context(app), httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url='https://app.example.test') as client:
            metadata = await client.get('/.well-known/oauth-protected-resource/mcp/daily')
            self.assertEqual(metadata.status_code, 200, metadata.text)
            self.assertEqual(metadata.json()['resource'], self.cfg.resource)
            self.assertEqual(metadata.json()['authorization_servers'], [self.cfg.issuer])
            headers = {'Accept': 'application/json, text/event-stream'}
            message = {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}
            unauthorized = await client.post('/mcp/daily', headers=headers, json=message)
            self.assertEqual(unauthorized.status_code, 401)
            self.assertIn('resource_metadata=', unauthorized.headers['www-authenticate'])
            self.assertEqual(self.paths, [])
            for token, expected in (('first-user', '1'), ('second-user', '2')):
                auth = headers | {'Authorization': 'Bearer ' + token}
                tools = await client.post('/mcp/daily', headers=auth, json=message)
                self.assertEqual(tools.status_code, 200, tools.text)
                tool_list = tools.json()['result']['tools']
                self.assertEqual({tool['name'] for tool in tool_list}, {'read_daily_plan', 'read_daily_review'})
                for tool in tool_list:
                    self.assertTrue(tool['annotations']['readOnlyHint'])
                    self.assertEqual(set(tool['inputSchema']['properties']), {'day'})
                call = await client.post('/mcp/daily', headers=auth, json={
                    'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call',
                    'params': {'name': 'read_daily_plan', 'arguments': {'day': '2026-10-10'}}})
                self.assertEqual(call.status_code, 200, call.text)
                self.assertFalse(call.json()['result']['isError'], call.text)
                result = json.loads(call.json()['result']['content'][0]['text'])
                self.assertEqual(result['content']['items'], [{'title': expected}])
            self.body['active'] = False
            denied = await client.post('/mcp/daily', headers=auth, json=message)
            self.assertEqual(denied.status_code, 401)
            self.assertEqual(sum(path.startswith('/api/internal/daily-read/') for path in self.paths), 2)

    def test_default_off(self):
        with self.assertRaises(ValueError):
            RemoteConfig.load({})
        with self.assertRaises(ValueError):
            RemoteConfig.load({'DAILY_REMOTE_ENABLED': 'true', 'DAILY_OAUTH_ISSUER': 'http://app.example.test'})
