"""四层家庭隔离测试（delta-spec 用例 3；§5.6）。"""
import pytest

from app.connectors.backend_client import BackendClient
from app.connectors.db_reader import DbReader
from app.models.errors import FamilyScopeError
from tests.conftest import CTX_FULL, FakeLLM, collect, make_decomposer


def test_layer2_backend_headers_reject_non_int_without_http() -> None:
    """连接器闸门：_headers 非 int 抛 TypeError 且不产生任何 HTTP 流量。"""
    client = BackendClient()
    for bad in ("abc", None, 1.5):
        with pytest.raises(TypeError):
            client._headers(bad)  # noqa: SLF001


async def test_layer2_db_reader_reject_non_int() -> None:
    """数据闸门：db_reader 运行时拒绝非 int family_id。"""
    reader = DbReader()
    with pytest.raises(TypeError):
        await reader.get_context("1")  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        await reader.get_recent_completed(1, 1.0)  # type: ignore[arg-type]


async def test_layer1_orchestrator_family_scope_46000_zero_llm() -> None:
    """编排首行闸门：家庭作用域不匹配 → error 帧 46000，零 LLM 调用、零写操作。"""
    expected_msg = FamilyScopeError().message
    llm = FakeLLM([])
    dec, be = make_decomposer(llm, CTX_FULL, auth_error=FamilyScopeError())
    frames = await collect(dec.stream(family_id=99, title="大扫除", access_token="tok"))
    assert [f["event"] for f in frames] == ["error"]
    assert frames[0]["data"] == {"code": 46000, "msg": expected_msg}
    assert llm.calls == [] and not be.calls
