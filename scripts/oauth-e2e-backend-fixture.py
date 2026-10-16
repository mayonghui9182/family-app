"""模拟内部每日来源；只供随机隔离 Docker 网络验收，无业务数据。"""

import hmac
import json
import os
import re
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        match = re.fullmatch(r'/api/internal/daily-read/([12])/(plans|reviews)/(\d{4}-\d{2}-\d{2})', self.path)
        valid = hmac.compare_digest(self.headers.get('X-Internal-Secret', ''), os.environ['TEST_INTERNAL_SECRET'])
        self.send_response(200 if match and valid else 401)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.end_headers()
        result = {'code': 40100, 'data': None}
        if match and valid:
            result = {'code': 0, 'data': {'content': {'items': [{'title': '用户' + match[1] + '模拟计划',
                'key': 'private:' + match[1], 'familyId': 10}], 'baseline': True}, 'feedback': '不可外发的模拟反馈'}}
        self.wfile.write(json.dumps(result).encode())

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    HTTPServer(('0.0.0.0', 18088), Handler).serve_forever()
