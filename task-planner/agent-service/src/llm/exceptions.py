"""LLM 适配层领域异常（tasks.md 7.2）。

调用方（拆解链路/每日总结）按异常类型决定降级行为：
- `LLMOutputUnparsable`：输出无法解析为 JSON，拆解链路发 error 事件
- `LLMCallFailed`：传输/接口级失败（超时、鉴权、限流等）
"""

from __future__ import annotations


class LLMCallFailed(Exception):
    """LLM 调用失败：网络超时、HTTP 错误、配置缺失等。"""


class LLMOutputUnparsable(Exception):
    """LLM 输出无法解析为 JSON：直接 parse 与正则提取均失败。"""

    def __init__(self, raw_output: str) -> None:
        # 保留原始输出便于日志排障（日志侧自行截断）
        self.raw_output = raw_output
        super().__init__("LLM 输出无法解析为 JSON")
