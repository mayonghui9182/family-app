"""微信公众号服务器配置验签（手写协议，无 SDK，design.md D1）。

算法：将 token、timestamp、nonce 三个字符串按字典序排序后拼接，
SHA-1 摘要与 signature 比对（恒定时间比较防时序侧信道）。
"""
import hashlib
import hmac


def calc_signature(token: str, timestamp: str, nonce: str) -> str:
    """按微信规则计算签名：sorted([token, timestamp, nonce]) 拼接后 SHA-1。"""
    raw = "".join(sorted([token, timestamp, nonce]))
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()  # noqa: S324  # 微信平台规定 SHA-1


def verify_signature(token: str, timestamp: str, nonce: str, signature: str) -> bool:
    """校验签名：四要素齐全且摘要匹配才放行。"""
    if not token or not timestamp or not nonce or not signature:
        return False
    expected = calc_signature(token, timestamp, nonce)
    return hmac.compare_digest(expected, signature)
