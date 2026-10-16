"""安全工具：恒定时间比较。"""
import hmac


def constant_time_equals(a: str, b: str) -> bool:
    """X-Internal-Secret 恒定时间比较（防时序侧信道）。"""
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))
