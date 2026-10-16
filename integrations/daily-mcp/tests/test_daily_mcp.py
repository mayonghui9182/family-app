"""仅使用本地模拟接口与虚构令牌，不连接业务库或外部服务。"""

import json
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from typing import ClassVar

from daily_mcp import DailyClient


class Handler(BaseHTTPRequestHandler):
    calls: ClassVar[list] = []
    status = 200
    payload: ClassVar[dict] = {'code': 0, 'data': {'date': '2026-10-10', 'kind': 'PLAN', 'feedback': '私人反馈',
                'userId': 2, 'content': {'baseline': False, 'initialItems': [{'title': '隐藏快照'}],
                'items': [{'title': '模拟任务', 'key': 'private:123', 'familyId': 8, 'focus': True},
                          {'unavailable': True, 'title': '已撤权标题'}]}}}

    def do_GET(self):
        type(self).calls.append((self.path, self.headers.get('X-Access-Token')))
        self.send_response(type(self).status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Location', '/should-not-follow')
        self.end_headers()
        self.wfile.write(json.dumps(type(self).payload).encode())

    def log_message(self, *args):
        pass


class DailyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.worker = Thread(target=cls.http.serve_forever, daemon=True)
        cls.worker.start()

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()
        cls.worker.join()

    def setUp(self):
        Handler.calls.clear()
        Handler.status = 200
        self.env = {'DAILY_MCP_ENABLED': 'true', 'DAILY_MCP_ACCESS_TOKEN': 'a' * 64,
                    'DAILY_MCP_BACKEND_URL': f'http://127.0.0.1:{self.http.server_port}'}
        self.client = DailyClient(self.env)

    def test_default_off_and_config(self):
        for changes in ({'DAILY_MCP_ENABLED': 'false'}, {'DAILY_MCP_ACCESS_TOKEN': 'bad'},
                        {'DAILY_MCP_BACKEND_URL': 'http://example.com'},
                        {'DAILY_MCP_BACKEND_URL': 'https://user:pass@example.com'},
                        {'DAILY_MCP_BACKEND_URL': 'https://example.com/api?token=x'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                DailyClient(self.env | changes)
        self.assertEqual(Handler.calls, [])

    def test_minimal_read_and_revocation(self):
        result = self.client.read('plans', '2026-10-10')
        self.assertEqual(result['content']['items'], [{'title': '模拟任务', 'focus': True}, {'unavailable': True}])
        self.assertFalse(result['content']['baseline'])
        for private in ('私人反馈', '隐藏快照', 'private:123', '已撤权标题', 'userId', 'familyId'):
            self.assertNotIn(private, json.dumps(result, ensure_ascii=False))
        self.assertEqual(Handler.calls, [('/api/daily/plans/2026-10-10', 'a' * 64)])

    def test_invalid_dates_and_route(self):
        for day in ('2026-02-30', '../settings', '20261010', None):
            self.assertIn('error', self.client.read('plans', day))
        self.assertIn('error', self.client.read('settings', '2026-10-10'))
        self.assertEqual(Handler.calls, [])

    def test_auth_error_and_no_redirect(self):
        for status in (401, 403, 302, 500):
            Handler.status = status
            self.assertIn('error', self.client.read('reviews', '2026-10-10'))
        self.assertEqual(len(Handler.calls), 4)
        self.assertTrue(all(path.endswith('/reviews/2026-10-10') for path, _ in Handler.calls))

    def test_missing_and_source_failure(self):
        original = Handler.payload
        try:
            Handler.payload = {'code': 0, 'data': {'missing': True}}
            self.assertTrue(self.client.read('plans')['missing'])
            for payload in ({'code': 50381, 'msg': 'secret-body', 'data': None},
                            {'code': 0, 'data': {'content': {'items': 'invalid'}}},
                            {'code': 0, 'data': []}):
                Handler.payload = payload
                result = self.client.read('plans')
                self.assertIn('error', result)
                self.assertNotIn('secret-body', json.dumps(result))
        finally:
            Handler.payload = original


if __name__ == '__main__':
    unittest.main()
