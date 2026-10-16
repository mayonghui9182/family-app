"""验证微信前端承接页与服务端回调分流，不创建消息或业务数据。"""

import argparse
import hashlib
import os
import secrets
import time
from urllib.parse import urlencode, urlparse
import httpx


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://nginx")
    args = parser.parse_args()
    base = args.gateway.rstrip("/")
    client = httpx.Client(timeout=15, follow_redirects=False)

    def request(path: str, method: str = "GET"):
        response = client.request(method, base + path)
        return response.status_code, response.headers, response.text

    def check(label: str, valid: bool) -> None:
        if not valid:
            raise AssertionError(label)
        print("PASS", label, flush=True)

    for path in ["/wechat/bind", "/wechat/bind?ticket=routing-check",
                 "/wechat/callback", "/wechat/callback?next=/tasks"]:
        status, headers, body = request(path)
        check(path.split("?")[0] + " 前端页面",
              status == 200 and "text/html" in headers.get("Content-Type", "")
              and 'id="app"' in body)

    status, _, _ = request("/wechat/callback", "HEAD")
    check("登录承接页 HEAD", status == 200)
    status, headers, _ = request("/wechat/authorize")
    check("授权入口保持微信跳转",
          status == 302 and urlparse(headers.get("Location", "")).hostname == "open.weixin.qq.com")
    status, _, _ = request("/wechat/oauth/callback")
    check("无效授权回调仍由微信服务校验", status == 400)
    status, _, _ = request("/wechat/callback", "POST")
    check("未签名消息仍被拒绝", status == 403)

    token = os.environ["WECHAT_TOKEN"]
    timestamp = str(int(time.time()))
    nonce = secrets.token_hex(8)
    echo = "gateway-routing-" + secrets.token_hex(8)
    signature = hashlib.sha1("".join(sorted([token, timestamp, nonce])).encode()).hexdigest()
    query = urlencode({"signature": signature, "timestamp": timestamp, "nonce": nonce, "echostr": echo})
    status, _, body = request("/wechat/callback?" + query)
    check("合法服务端 GET 验签原样返回", status == 200 and body == echo)
    status, _, _ = request("/wechat/callback?signature=invalid&echostr=routing-check")
    check("非法服务端 GET 验签仍被拒绝", status == 403)
    status, _, _ = request("/api/internal/wechat/bind")
    check("内部接口仍禁止公网访问", status in (403, 404))
    client.close()


if __name__ == "__main__":
    main()

