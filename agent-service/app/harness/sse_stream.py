"""SSE 事件格式化与 reasoning 节流缓冲。

产品行为（不砍）：reasoning 逐 token 到达，普通模式每 100ms flush 一帧；
X-Prefers-Reduced-Motion 时攒 3s 合并成一帧，降低老人界面闪动。
"""
import json
import time
from typing import Any

# 规格 §3.2 五事件：classification / reasoning / proposal / done / error
EVENT_NAMES = ("classification", "reasoning", "proposal", "done", "error")

REASONING_FLUSH_INTERVAL = 0.1  # 普通模式 100ms
REDUCED_MOTION_INTERVAL = 3.0  # 减动效模式攒 3s


def format_sse(event: str, data: dict[str, Any]) -> str:
    """格式化为 SSE 帧：event: <name>\\ndata: <紧凑 JSON>\\n\\n。"""
    body = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {body}\n\n"


class SSEStreamWriter:
    """reasoning token 缓冲器：feed() 同步收集，take_frames() 按 interval 出帧。"""

    def __init__(self, reduced_motion: bool = False) -> None:
        self.interval = REDUCED_MOTION_INTERVAL if reduced_motion else REASONING_FLUSH_INTERVAL
        self._buffer: list[str] = []
        self._last_take = time.monotonic()

    def feed(self, token: str) -> None:
        """LLM on_token 回调入口（同步、零阻塞）。"""
        self._buffer.append(token)

    @property
    def pending(self) -> bool:
        return bool(self._buffer)

    def take_frames(self) -> list[str]:
        """距上次取帧超过 interval 时，把积压 token 合成一帧取走；否则空列表。"""
        now = time.monotonic()
        if not self._buffer or (now - self._last_take) < self.interval:
            return []
        delta = "".join(self._buffer)
        self._buffer.clear()
        self._last_take = now
        return [format_sse("reasoning", {"delta": delta})]

    def drain(self) -> list[str]:
        """流结束时取走剩余 token（无视 interval）。"""
        if not self._buffer:
            return []
        delta = "".join(self._buffer)
        self._buffer.clear()
        return [format_sse("reasoning", {"delta": delta})]
