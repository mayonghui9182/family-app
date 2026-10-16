"""连续修改必须包含原需求，并对目标日期查询天气。"""
import json
from datetime import date, timedelta

from app.services.decomposer import Decomposer
from tests.conftest import CTX_COLD, VALID_DECOMPOSE_JSON, FakeLLM, FakeWeather, collect, make_decomposer


async def test_second_round_keeps_original_context() -> None:
    llm = FakeLLM([{"kind": "json", "text": VALID_DECOMPOSE_JSON}])
    dec, backend = make_decomposer(llm, CTX_COLD)
    backend.categories = []
    backend.session_context = {
        "title": "北京全家野餐", "messages": [{"role": "user", "content": "爷爷也参加"}],
        "proposed_tasks": [{"title": "准备野餐食物"}], "revision": "2026-09-15T00:00:00Z",
    }
    frames = await collect(dec.stream(family_id=1, title="改到下周，爷爷不去", session_id=9))
    assert frames[-1]["event"] == "done"
    prompt = json.dumps(llm.calls[0], ensure_ascii=False)
    assert "北京全家野餐" in prompt and "准备野餐食物" in prompt and "爷爷不去" in prompt
    assert backend.saved_proposal
    assert not backend.calls

async def test_weather_uses_explicit_target_date_and_latest_city() -> None:
    weather = FakeWeather()
    dec, _ = make_decomposer(FakeLLM([]), CTX_COLD, weather=weather)
    target = date.today() + timedelta(days=2)
    await dec._fetch_weather("改到杭州", "北京野餐", target)
    assert weather.calls == [("杭州", target.isoformat())]
    assert Decomposer._target_date("明天去", "2030-01-01") == date.today() + timedelta(days=1)
