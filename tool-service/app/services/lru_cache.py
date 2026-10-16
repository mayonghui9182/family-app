"""进程内 TTL+LRU 缓存（OrderedDict + threading.Lock）。maxsize=1024，TTL 秒级。"""
import time
from collections import OrderedDict
from threading import Lock
from typing import TypeVar

K = TypeVar("K")
V = TypeVar("V")


class TTLLRUCache[K, V]:
    """泛型 TTL+LRU 缓存。get 自动检查过期并淘汰；set 满容量时淘汰最老项。"""

    def __init__(self, maxsize: int = 1024, ttl_seconds: int = 21600) -> None:
        self._data: OrderedDict[K, tuple[V, float]] = OrderedDict()
        self._lock = Lock()
        self.maxsize = maxsize
        self.ttl = ttl_seconds

    def get(self, key: K) -> V | None:
        with self._lock:
            item = self._data.get(key)
            if item is None:
                return None
            value, stored_at = item
            if time.time() - stored_at > self.ttl:
                del self._data[key]
                return None
            self._data.move_to_end(key)
            return value

    def set(self, key: K, value: V) -> None:
        with self._lock:
            if key in self._data:
                self._data.move_to_end(key)
            elif len(self._data) >= self.maxsize:
                self._data.popitem(last=False)
            self._data[key] = (value, time.time())

    def __len__(self) -> int:
        return len(self._data)
