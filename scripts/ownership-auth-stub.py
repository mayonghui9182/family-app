"""隔离验收的认证平台桩：仅固定两个测试身份，不读取任何真实账号。"""

import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    """不记录请求头或认证凭据。"""

    def log_message(self, *args):
        pass

    def do_GET(self):
        user = None
        if self.path == "/api/auth/me":
            token = self.headers.get("X-Access-Token", "")
            user = {"a" * 64: 1, "b" * 64: 2}.get(token)
        elif self.path.startswith("/api/internal/work-users/"):
            if hmac.compare_digest(self.headers.get("X-Internal-Secret", ""), os.environ["TEST_SECRET"]):
                raw = self.path.rsplit("/", 1)[-1]
                if raw in ("1", "2"):
                    user = int(raw)
        body = json.dumps({"code": 0, "data": {"userId": user}} if user else {"code": 40101}).encode()
        self.send_response(200 if user else 401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
