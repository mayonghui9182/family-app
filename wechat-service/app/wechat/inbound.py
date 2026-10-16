"""入站消息明文 XML 解析与被动回复构建（Change 14 Task 9）。

为什么手写 xml.etree 而不引 defusedxml：微信明文推送字段固定、报文极小，
「原始字节拒 DOCTYPE/ENTITY + 大小上限」两道防线已在解析器动手前挡掉
XXE 与实体膨胀炸弹，不为此新增运行时依赖。
"""
from __future__ import annotations

import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import UTC, datetime

# 微信明文推送正常远小于 4KB；超 64KB 一律按攻击/垃圾流量拒收
MAX_BODY_BYTES = 64 * 1024
# 大小写不敏感：匹配前把原始字节统一 upper() 再查这些标记
FORBIDDEN_MARKERS = (b"<!DOCTYPE", b"<!ENTITY")

RECEIPT_REPLY = "收到，正在理解你的意思，任务草稿整理好后会在微信里发给你"
TEXT_ONLY_HINT = "目前我只能看懂文字，请直接打字发给我"

# backend Verdict.reason → 中文话术（design.md D3 裁决表 + D8 话术铁律：第二人称、不用术语、给下一步）。
# 与 D3 表的三处有意偏离：multi_family 的 Verdict 无计数字段 ⇒ 措辞去「N 个」改「多个」；
# intake_disabled 表内未定义 ⇒ 借用 D8 ai_disabled 话术；duplicate 与未知 reason ⇒ 静默回空串（防双显、不许诺）。
REASON_REPLIES: dict[str, str] = {
    "not_bound": "你还不是家庭应用的成员，请先在应用里绑定微信",
    "no_family": "你还没有加入任何家庭，请先在应用里加入家庭",
    "role_denied": "这个功能需要成年人成员，请让家人帮你添加",
    "multi_family": "你有多个家庭，请打开应用选好家庭后再用 AI 拆解",
    "intake_disabled": "AI 拆解功能暂时关闭了，你可以先在应用里手动添加任务",
}


class InboundParseError(Exception):
    """解析失败或被防线拦下；调用方一律按「不受理」处理，只记日志不惊动微信。"""


@dataclass(frozen=True)
class InboundMessage:
    """微信明文推送里本链路用到的字段（text / event 两类消息）。"""

    msg_type: str
    from_user_name: str
    to_user_name: str
    msg_id: str | None
    content: str | None
    create_time: int | None
    event: str | None


def parse_inbound_message(raw: bytes) -> InboundMessage:
    """解析推送 XML：超限、含 DOCTYPE/ENTITY、语法失败都在进解析器前后被拒。"""
    if len(raw) > MAX_BODY_BYTES:
        raise InboundParseError(f"消息体超过 {MAX_BODY_BYTES} 字节上限")
    if any(marker in raw.upper() for marker in FORBIDDEN_MARKERS):
        raise InboundParseError("XML 含 DOCTYPE/ENTITY，按 XXE 防线拒绝")
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as exc:
        raise InboundParseError(f"XML 解析失败：{exc}") from exc

    def field(tag: str) -> str | None:
        node = root.find(tag)
        return (node.text or "").strip() if node is not None else None

    create_raw = field("CreateTime")
    create_time = int(create_raw) if create_raw is not None and create_raw.isdigit() else None
    return InboundMessage(
        msg_type=(field("MsgType") or "").lower(),
        from_user_name=field("FromUserName") or "",
        to_user_name=field("ToUserName") or "",
        msg_id=field("MsgId"),
        content=field("Content"),
        create_time=create_time,
        event=field("Event"),
    )


def to_received_at(create_time: int | None) -> str:
    """CreateTime(unix 秒) → ISO-8601 Z：backend 用 Instant.parse，只认 Z 后缀；缺失/脏值取当前时间。"""
    ts = create_time if create_time is not None else int(datetime.now(tz=UTC).timestamp())
    return datetime.fromtimestamp(ts, tz=UTC).isoformat().replace("+00:00", "Z")


def reply_text_for_verdict(accepted: bool, reason: str | None) -> str | None:
    """Verdict → 回复文案；None = 回空串（duplicate 与未知 reason 的有意静默）。"""
    if accepted:
        return RECEIPT_REPLY
    return REASON_REPLIES.get(reason or "")


def build_text_reply(msg: InboundMessage, content: str) -> str:
    """被动回复 XML（明文模式）：方向是公众号 → 用户，To/From 与推送互换。

    content 只可能是本模块的固定话术常量，不含用户输入，CDATA 包裹无注入面。
    """
    now = int(datetime.now(tz=UTC).timestamp())
    return (
        "<xml>"
        f"<ToUserName><![CDATA[{msg.from_user_name}]]></ToUserName>"
        f"<FromUserName><![CDATA[{msg.to_user_name}]]></FromUserName>"
        f"<CreateTime>{now}</CreateTime>"
        "<MsgType><![CDATA[text]]></MsgType>"
        f"<Content><![CDATA[{content}]]></Content>"
        "</xml>"
    )
