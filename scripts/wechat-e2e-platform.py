"""隔离验收专用微信/LLM 模拟器；不能用于生产，永不访问真实微信或模型。"""

import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

STATE: dict[str, Any] = {"errcode": 0, "sends": 0, "turns": 0}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format: str, *args: Any) -> None:
        """不打印请求正文、签名或用户标识。"""

    def respond(self, value: Any) -> None:
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        if self.path.startswith("/cgi-bin/token"):
            self.respond({"access_token": "isolated-platform-token", "expires_in": 7200})
        else:
            self.respond(STATE)

    def do_POST(self) -> None:
        payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        if self.path == "/test/control":
            STATE["errcode"] = int(payload["errcode"])
            self.respond({"ok": True})
        elif self.path.startswith("/cgi-bin/message"):
            STATE["sends"] += 1
            self.respond({"errcode": STATE["errcode"], "errmsg": "isolated test"})
        else:
            prompt = payload["messages"][-1]["content"]
            if "last_message_id 原样设为" in prompt:
                match = re.search(r"last_message_id 原样设为 (\d+)", prompt)
                assert match
                result = {"confirmed_facts": [], "open_questions": [], "user_decisions": [],
                          "candidate_overview": [], "last_message_id": int(match.group(1))}
            else:
                STATE["turns"] += 1
                text = prompt.split("【本轮用户明确输入】\n", 1)[1].split("\n\n", 1)[0]
                result = {"action": "ASK_CLARIFICATION", "reply": "请告诉我哪天买菜？", "slot_patch": {}}
                if text != "买菜":
                    result = {"action": "PRESENT_CANDIDATE", "reply": "请确认当前候选版本。",
                              "tasks": [{"title": "买菜", "priority": "MEDIUM", "description": text}],
                              "slot_patch": {}}
            content = json.dumps(result, ensure_ascii=False)
            chunk = {"id": "isolated", "object": "chat.completion.chunk", "created": 1,
                     "model": "isolated", "choices": [{"index": 0, "delta": {"content": content},
                                                          "finish_reason": None}]}
            data = ("data: " + json.dumps(chunk, ensure_ascii=False) + "\n\ndata: [DONE]\n\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
