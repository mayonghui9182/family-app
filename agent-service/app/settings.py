"""PydanticSettings 强类型配置（敏感字段 SecretStr）。"""
from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """agent-service 运行配置；内部密钥必填，其他外部能力可按既有规则降级。"""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # LLM（OpenAI 兼容 SDK）
    deepseek_api_key: SecretStr = SecretStr("")
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"
    mcp_timeout: int = 30
    summary_timeout_per_category: int = 60

    # 公众号会话使用独立预算，不改变其他 AI 拆解与总结的默认行为。
    conversation_timeout_seconds: float = Field(default=35, gt=0, le=40)
    conversation_llm_connect_timeout_seconds: float = Field(default=8, gt=0, le=15)
    conversation_llm_read_timeout_seconds: float = Field(default=25, gt=0, le=35)
    conversation_llm_sdk_max_retries: int = Field(default=0, ge=0, le=1)
    conversation_llm_max_attempts: int = Field(default=1, ge=1, le=2)
    conversation_llm_thinking: Literal["disabled", "enabled", "omit"] = "disabled"
    conversation_llm_max_tokens: int = Field(default=4096, ge=256, le=16384)

    # 内部 API 与连接器
    internal_api_secret: SecretStr
    backend_base_url: str = "http://backend:8080"
    db_agent_reader_url: str = ""
    tool_service_url: str = "http://tool-service:8800"
    family_id_scope_enforce: bool = True
    draft_expire_hours: int = 24

    @field_validator("internal_api_secret")
    @classmethod
    def validate_internal_api_secret(cls, value: SecretStr) -> SecretStr:
        """内部调用密钥禁止空白，避免服务以半可用状态启动。"""
        if not value.get_secret_value().strip():
            raise ValueError("INTERNAL_API_SECRET 不能为空")
        return value


@lru_cache
def get_settings() -> Settings:
    """进程级单例 Settings。"""
    # 必填密钥由 BaseSettings 在运行时从环境变量注入，mypy 无法感知。
    return Settings()  # type: ignore[call-arg]
