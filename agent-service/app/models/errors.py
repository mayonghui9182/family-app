"""agent-service 统一错误体系（全中文 msg，适配老人显示）。"""
class AgentServiceError(Exception):
    """业务错误基类：code 为 5 位业务码，message 全中文。"""

    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class ParamError(AgentServiceError):
    """40099 参数校验失败。"""

    def __init__(self, message: str = "参数校验失败") -> None:
        super().__init__(40099, message)


class InternalSecretError(AgentServiceError):
    """40102 内部秘钥不正确（B 组端点）。"""

    def __init__(self) -> None:
        super().__init__(40102, "内部秘钥不正确")


class AuthError(AgentServiceError):
    """40103 登录已过期（A-1 身份校验失败，不调 LLM）。"""

    def __init__(self) -> None:
        super().__init__(40103, "登录已过期，请重新登录")


class FamilyScopeError(AgentServiceError):
    """46000 家庭作用域不匹配（token 有效但不含该 family_id）。"""

    def __init__(self) -> None:
        super().__init__(46000, "您不在当前所选的家庭中，请切换家庭后再试")


class RoleDeniedError(AgentServiceError):
    """40302 角色无权限（孩子成员不支持 AI 助手 / 手动总结）。"""

    def __init__(self, message: str = "孩子成员暂不能使用 AI 助手哦，请让爸爸妈妈来操作") -> None:
        super().__init__(40302, message)


class LLMTimeoutError(AgentServiceError):
    """56001 拆解超时（MCP_TIMEOUT 秒）。"""

    def __init__(self) -> None:
        super().__init__(56001, "AI 拆解超时了，请稍后再试一次")


class BackendWriteError(AgentServiceError):
    """56003 写入后端失败（触发已写数据回滚软取消）。"""

    def __init__(self) -> None:
        super().__init__(56003, "任务保存失败了，请稍后再试")


class SummaryAlreadyRanError(AgentServiceError):
    """46502 当日总结锁已占用（同日已跑且未 force），对齐 backend SUMMARY_ALREADY_RAN。"""

    def __init__(self) -> None:
        super().__init__(46502, "今日已自动总结过，仍想强制重跑请设置 force=true")
