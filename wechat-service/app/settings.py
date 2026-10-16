"""PydanticSettings 强类型配置（敏感字段 SecretStr），与 agent-service 同款。"""

from functools import lru_cache
from typing import Self

from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """wechat-service 运行配置；内部密钥必填，微信能力仍可按既有规则降级。"""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # 微信公众号凭证（测试号/服务号后台获取；未配置时仅 healthz 可用）
    wechat_app_id: SecretStr = SecretStr("")
    wechat_app_secret: SecretStr = SecretStr("")
    wechat_token: SecretStr = SecretStr("")

    # 公网回调基址（隧道域名），如 https://xxx.r6.cpolar.cn；OAuth redirect_uri 与菜单 URL 基于它拼接
    oauth_callback_base: str = "http://localhost"
    # H5 前端基址（绑定页/登录落地页）；与回调同源（nginx 同域）时保持一致
    h5_base_url: str = "http://localhost"
    # 微信 API 基址（测试可覆写指向 mock）
    wechat_api_base: str = "https://api.weixin.qq.com"
    wechat_open_base: str = "https://open.weixin.qq.com"

    # backend 内部连接器
    backend_base_url: str = "http://backend:8080"
    internal_api_secret: SecretStr

    # Change 14 入站受理开关（env WECHAT_INBOUND_ENABLED），与 backend 同名开关是两道独立闸门，默认关：
    # 关 = 验签通过后直接回空串，不解析 XML、不调 backend（完全静默）；开 = 真实受理转交
    wechat_inbound_enabled: bool = False
    # 新版持续会话入口开关；默认关闭，完整互斥校验及 worker 参数在 Task 2.3 补齐
    wechat_conversation_enabled: bool = False

    # Change 12 模板消息：测试号后台创建模板后拿到的 template_id；空串 = 该事件降级 skipped
    wechat_tpl_task_digest_id: str = ""
    wechat_tpl_summary_id: str = ""
    wechat_tpl_task_assign_id: str = ""
    # Change 13 日程早报模板 ID（env 名钉死 WECHAT_TPL_EVENT_DIGEST_ID）；空串同样静默跳过
    wechat_tpl_event_digest_id: str = ""

    @field_validator("internal_api_secret")
    @classmethod
    def validate_internal_api_secret(cls, value: SecretStr) -> SecretStr:
        """内部调用密钥禁止空白，避免服务以半可用状态启动。"""
        if not value.get_secret_value().strip():
            raise ValueError("INTERNAL_API_SECRET 不能为空")
        return value

    @model_validator(mode="after")
    def validate_wechat_consumption_mode(self) -> Self:
        """旧入站和新会话模式不可同时消费同一条微信消息。"""
        if self.wechat_inbound_enabled and self.wechat_conversation_enabled:
            raise ValueError("WECHAT_INBOUND_ENABLED 与 WECHAT_CONVERSATION_ENABLED 不能同时开启")
        return self


@lru_cache
def get_settings() -> Settings:
    """进程级单例 Settings。"""
    # 必填密钥由 BaseSettings 在运行时从环境读取，静态分析无法识别
    return Settings()  # type: ignore[call-arg]
