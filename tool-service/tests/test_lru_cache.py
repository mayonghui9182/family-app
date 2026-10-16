"""TTLLRUCache 4 用例：容量/TTL/并发/边界。"""
import concurrent.futures

import pytest

from app.services.lru_cache import TTLLRUCache


def test_lru_eviction_on_maxsize() -> None:
    """maxsize=2，写入 3 个 → 最早被淘汰。"""
    c = TTLLRUCache[str, int](maxsize=2, ttl_seconds=99999)
    c.set("a", 1)
    c.set("b", 2)
    c.set("c", 3)
    assert c.get("a") is None
    assert c.get("b") == 2 and c.get("c") == 3


def test_ttl_expiry_after_7_hours(monkeypatch: pytest.MonkeyPatch) -> None:
    """freeze_time 7h（>6h TTL）→ get 返回 None。"""
    fake = {"t": 1000000.0}

    def fake_time() -> float:
        return fake["t"]

    monkeypatch.setattr("app.services.lru_cache.time.time", fake_time)
    c = TTLLRUCache[str, int](ttl_seconds=21600)
    c.set("x", 99)
    fake["t"] += 7 * 3600 + 1
    assert c.get("x") is None


def test_ttl_zero_always_expired(monkeypatch: pytest.MonkeyPatch) -> None:
    """TTL=0 → 每次 time.diff>=1 则过期。"""
    fake = {"t": 100.0}

    def fake_time() -> float:
        return fake["t"]

    monkeypatch.setattr("app.services.lru_cache.time.time", fake_time)
    c = TTLLRUCache[str, int](ttl_seconds=0)
    c.set("y", 42)
    fake["t"] += 1  # time diff=1 > TTL=0
    assert c.get("y") is None


def test_concurrent_no_deadlock() -> None:
    """并发 100 线程读写 → 无硬锁/KeyError。"""
    c = TTLLRUCache[int, int](maxsize=50)
    errors: list[Exception] = []

    def worker(i: int) -> None:
        try:
            c.set(i % 20, i)
            _ = c.get(i % 20)
        except Exception as e:
            errors.append(e)

    with concurrent.futures.ThreadPoolExecutor(max_workers=100) as executor:
        futures = [executor.submit(worker, i) for i in range(500)]
        for f in futures:
            f.result()
    assert not errors
