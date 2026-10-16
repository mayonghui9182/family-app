"""新镜像跨服务隔离验收与回滚演练；外部平台模拟，业务容器和数据库不在操作范围。"""

import hashlib
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "familyapp-wechat-e2e-" + uuid.uuid4().hex[:12]
LABEL = "familyapp.wechat-e2e=" + PREFIX
SECRET = uuid.uuid4().hex + uuid.uuid4().hex
CREATED: list[str] = []


def docker(*args: str, input_text: str | None = None) -> str:
    result = subprocess.run(["docker", *args], input=input_text, text=True, capture_output=True, check=False)
    if result.returncode:
        # 不回显命令参数、日志或环境，避免凭据进入终端。
        raise RuntimeError("隔离 Docker 操作失败: " + args[0])
    return result.stdout.strip()


def start(service: str, image: str, environment: dict[str, str], *extra: str,
          command: tuple[str, ...] = ()) -> str:
    name = PREFIX + "-" + service
    args = ["run", "-d", "--name", name, "--label", LABEL, "--network", PREFIX,
            "--network-alias", service]
    for key, value in environment.items():
        args.extend(["-e", key + "=" + value])
    docker(*args, *extra, image, *command)
    CREATED.append(name)
    return name


def port(service: str, container_port: int) -> str:
    mapped = docker("port", PREFIX + "-" + service, str(container_port)).splitlines()[0]
    return "http://" + mapped


def request(url: str, data: Any = None, headers: dict[str, str] | None = None) -> tuple[int, str]:
    body = None if data is None else (data if isinstance(data, bytes) else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers or {}), timeout=5) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def wait(check: Any, label: str, seconds: int = 60) -> Any:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (OSError, urllib.error.URLError, RuntimeError):
            pass
        time.sleep(0.5)
    raise AssertionError("验收超时: " + label)


def sql(statement: str) -> str:
    return docker("exec", "-i", PREFIX + "-postgres", "psql", "-U", "postgres", "-d", "isolated_wechat",
                  "-At", "-v", "ON_ERROR_STOP=1", input_text=statement)


def cleanup() -> None:
    if not re.fullmatch(r"familyapp-wechat-e2e-[a-f0-9]{12}", PREFIX):
        raise RuntimeError("拒绝非隔离资源清理")
    for name in reversed(CREATED):
        labels = json.loads(docker("inspect", "--format", "{{json .Config.Labels}}", name))
        if not name.startswith(PREFIX + "-") or labels.get("familyapp.wechat-e2e") != PREFIX:
            raise RuntimeError("拒绝删除不属于本次验收的容器")
        docker("rm", "-f", "-v", name)
    docker("network", "rm", PREFIX)


def main() -> None:
    docker("network", "create", "--label", LABEL, PREFIX)
    backend_environment = {
        "SPRING_PROFILES_ACTIVE": "prod", "INTERNAL_API_SECRET": SECRET,
        "SPRING_DATASOURCE_URL": "jdbc:postgresql://postgres:5432/isolated_wechat?stringtype=unspecified",
        "SPRING_DATASOURCE_USERNAME": "postgres", "SPRING_DATASOURCE_PASSWORD": SECRET,
        "AGENT_SERVICE_BASE_URL": "http://agent-service:8700", "WECHAT_SERVICE_BASE_URL": "http://wechat-service:8900",
        "WECHAT_CONVERSATION_ENABLED": "true", "WECHAT_CONVERSATION_WORKER_ENABLED": "true",
        "WECHAT_CONVERSATION_POLL_MS": "100", "WECHAT_CONVERSATION_POLL_INITIAL_MS": "1000",
        "WECHAT_CONVERSATION_RETRY_INITIAL_MS": "100", "WECHAT_NOTIFY_ENABLED": "true",
        "WECHAT_INTAKE_ENABLED": "false", "WECHAT_INBOUND_ENABLED": "false",
    }
    try:
        start("postgres", "postgres:16-alpine", {"POSTGRES_PASSWORD": SECRET, "POSTGRES_DB": "isolated_wechat"})
        wait(lambda: docker("exec", PREFIX + "-postgres", "pg_isready", "-U", "postgres"), "独立 PostgreSQL")
        start("platform", "family-app-wechat-service", {}, "--network-alias", "frontend",
              "--network-alias", "tp-task-service", "--network-alias", "tp-agent-service",
              "-p", "127.0.0.1::9000", "-v", str(ROOT / "scripts/wechat-e2e-platform.py") + ":/test/platform.py:ro",
              "--entrypoint", "python", command=("/test/platform.py",))
        start("agent-service", "family-app-agent-service", {"INTERNAL_API_SECRET": SECRET,
              "DEEPSEEK_API_KEY": "isolated-no-real-key", "DEEPSEEK_BASE_URL": "http://platform:9000/v1",
              "BACKEND_BASE_URL": "http://backend:8080"})
        start("wechat-service", "family-app-wechat-service", {"INTERNAL_API_SECRET": SECRET,
              "WECHAT_APP_ID": "isolated", "WECHAT_APP_SECRET": "isolated", "WECHAT_TOKEN": "isolated",
              "WECHAT_API_BASE": "http://platform:9000", "WECHAT_CONVERSATION_ENABLED": "true",
              "WECHAT_INBOUND_ENABLED": "false", "BACKEND_BASE_URL": "http://backend:8080"})
        start("backend", "family-app-backend", backend_environment, "-p", "127.0.0.1::8080")
        backend = port("backend", 8080)
        wait(lambda: request(backend + "/actuator/health")[0] == 200, "真实 backend 健康", 90)
        start("gateway", "family-app-nginx", {}, "-p", "127.0.0.1::80")
        gateway = port("gateway", 80)
        wait(lambda: request(gateway + "/nginx-health")[0] == 200, "隔离网关")
        for path in ("/api/internal", "/api/internal/wechat/conversation/messages", "/api/internal/task-conversations/turn"):
            assert request(gateway + path, {}, {"X-Internal-Secret": SECRET})[0] in (403, 404)
        assert request(backend + "/api/internal/wechat/conversation/messages", {}, {"Content-Type": "application/json"})[0] == 401
        sql("INSERT INTO wechat_bind(user_id,openid) VALUES(1,'isolated-user');")
        sequence = 0
        passive_reply = ""

        def send(text: str, external: str | None = None) -> str:
            nonlocal sequence, passive_reply
            sequence += 1
            msg = external or str(sequence)
            stamp, nonce = str(int(time.time())), "isolated"
            signature = hashlib.sha1("".join(sorted(["isolated", stamp, nonce])).encode()).hexdigest()
            xml = ("<xml><ToUserName>isolated-account</ToUserName><FromUserName>isolated-user</FromUserName>"
                   f"<CreateTime>{stamp}</CreateTime><MsgType>text</MsgType><Content>{escape(text)}</Content><MsgId>{msg}</MsgId></xml>").encode()
            code, passive_reply = request(gateway + f"/wechat/callback?signature={signature}&timestamp={stamp}&nonce={nonce}", xml,
                              {"Content-Type": "application/xml"})
            assert code == 200
            wait(lambda: sql(f"SELECT processing_status FROM wechat_conversation_messages WHERE wechat_account_id='isolated-account' AND external_msg_id='{msg}'")
                 in ("PROCESSED", "SKIPPED"), "微信消息处理")
            return msg

        send("买菜")
        assert sql("SELECT COUNT(*) FROM tasks WHERE source='AGENT'") == "0"
        send("明天买菜")
        assert sql("SELECT MAX(current_revision) FROM wechat_task_conversations WHERE family_id=1") == "1"
        send("后天买菜")
        assert sql("SELECT MAX(current_revision) FROM wechat_task_conversations WHERE family_id=1") == "2"
        send("确认创建 1")
        assert sql("SELECT COUNT(*) FROM tasks WHERE source='AGENT'") == "0"
        created = send("确认创建 2")
        send("确认创建 2", created)
        send("确认创建 2")
        assert sql("SELECT COUNT(*) FROM tasks WHERE source='AGENT'") == "1"
        send("历史会话")
        send("继续 1")
        assert sql("SELECT COUNT(*) FROM wechat_task_conversations WHERE family_id=1 AND parent_conversation_id IS NOT NULL") == "1"
        send("取消会话")
        send("历史会话")
        send("删除 1")
        assert sql("SELECT COUNT(*) FROM wechat_task_conversations WHERE family_id=1 AND deleted_at IS NOT NULL") == "0"
        send("确认删除 1")
        assert sql("SELECT COUNT(*) FROM wechat_task_conversations WHERE family_id=1 AND deleted_at IS NOT NULL") == "1"
        sql("INSERT INTO families(name,created_by) VALUES('二二家',1); INSERT INTO family_members(family_id,user_id,role,joined_at) SELECT id,1,'OWNER',NOW() FROM families WHERE name='二二家';")
        family_b = sql("SELECT id FROM families WHERE name='二二家'")
        wait(lambda: int(sql("SELECT COUNT(*) FROM wechat_message_outbox WHERE status='SENT'")) > 0,
             "真实微信服务发送到模拟平台")
        assert json.loads(request(port("platform", 9000) + "/test/state")[1])["sends"] > 0
        send("切换到二二家")
        request(port("platform", 9000) + "/test/control", {"errcode": 45015}, {"Content-Type": "application/json"})
        send("明天买菜")
        send("确认创建")
        assert sql(f"SELECT COUNT(*) FROM tasks WHERE source='AGENT' AND family_id={family_b}") == "1"
        assert sql("SELECT COUNT(*) FROM tasks WHERE source='AGENT' AND family_id=1") == "1"
        wait(lambda: int(sql(f"SELECT COUNT(*) FROM wechat_message_outbox WHERE family_id={family_b} AND status='FAILED' AND wechat_error_code='45015' AND dedupe_key LIKE '%:result'")) == 1,
             "客服窗口错误终态")
        request(port("platform", 9000) + "/test/control", {"errcode": 0}, {"Content-Type": "application/json"})
        send("确认创建")
        assert "上次已成功创建 1 项任务" in passive_reply
        assert sql(f"SELECT COUNT(*) FROM wechat_task_confirmations WHERE family_id={family_b}") == "1"
        print("PASS: 真实客服发送、45015终态、下一次被动提示原创建结果、无重复确认")

        # 默认家庭消除了新消息等待；保留旧版已持久化 WAITING_FAMILY 的恢复夹具。
        sql("INSERT INTO wechat_conversation_messages(wechat_account_id,openid,external_msg_id,wechat_bind_id,user_id,direction,content,received_at,available_at,processing_status) SELECT 'isolated-account','isolated-user','waiting-replay',id,1,'INBOUND','明天买菜',NOW(),NOW(),'WAITING_FAMILY' FROM wechat_bind WHERE openid='isolated-user';")
        expires = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 300))
        sql(f"UPDATE wechat_chat_context SET pending_family_selection=jsonb_build_object('pending_message_id',(SELECT id FROM wechat_conversation_messages WHERE external_msg_id='waiting-replay'),'wechat_bind_id',wechat_bind_id,'route_revision',route_revision,'expires_at','{expires}','candidate_family_ids',jsonb_build_array(1,{family_b}));")
        selected = send("2")
        wait(lambda: sql("SELECT processing_status FROM wechat_conversation_messages WHERE external_msg_id='waiting-replay'") == "PROCESSED", "待选原消息回放")
        send("2", selected)
        assert sql("SELECT COUNT(*) FROM wechat_candidate_versions WHERE source_message_id=(SELECT id FROM wechat_conversation_messages WHERE external_msg_id='waiting-replay')") == "1"
        assert sql("SELECT COUNT(*) FROM tasks WHERE source='AGENT'") == "2"
        print("PASS: 已持久化WAITING_FAMILY按编号选择后原文只回放一次")
        send("切换到不存在的家庭")
        assert sql("SELECT current_family_id FROM wechat_chat_context") == family_b
        print("PASS: 网关拒绝内部接口、真实回调→backend→agent→微信发送、追问/修订/旧确认/重复确认/历史派生删除/跨家庭隔离")

        # 仅重启本次隔离 backend；已持久化消息不复制到旧 intake。
        backend_name = PREFIX + "-backend"
        docker("rm", "-f", backend_name)
        CREATED.remove(backend_name)
        backend_environment["WECHAT_CONVERSATION_ENABLED"] = "false"
        backend_environment["WECHAT_CONVERSATION_WORKER_ENABLED"] = "false"
        start("backend", "family-app-backend", backend_environment, "-p", "127.0.0.1::8080")
        backend = port("backend", 8080)
        wait(lambda: request(backend + "/actuator/health")[0] == 200, "关闭后的 backend", 90)
        sql(f"INSERT INTO wechat_conversation_messages(wechat_account_id,openid,external_msg_id,wechat_bind_id,user_id,family_id,direction,content,received_at,available_at,processing_status) SELECT 'isolated-account','isolated-user','rollback-held',id,1,{family_b},'INBOUND','明天买菜',NOW(),NOW(),'PENDING' FROM wechat_bind WHERE openid='isolated-user';")
        time.sleep(1)
        assert sql("SELECT processing_status FROM wechat_conversation_messages WHERE external_msg_id='rollback-held'") == "PENDING"
        assert sql("SELECT COUNT(*) FROM wechat_inbound_message") == "0"
        docker("rm", "-f", backend_name)
        CREATED.remove(backend_name)
        backend_environment["WECHAT_CONVERSATION_ENABLED"] = "true"
        backend_environment["WECHAT_CONVERSATION_WORKER_ENABLED"] = "true"
        start("backend", "family-app-backend", backend_environment, "-p", "127.0.0.1::8080")
        wait(lambda: sql("SELECT processing_status FROM wechat_conversation_messages WHERE external_msg_id='rollback-held'") == "PROCESSED", "恢复消费", 90)
        assert sql("SELECT COUNT(*) FROM tasks WHERE source='AGENT'") == "2"
        print("PASS: 关闭入口/worker保留消息，重启恢复消费，无旧队列复制、无额外任务")
    finally:
        cleanup()
        print("本次隔离容器、匿名测试卷和网络已清理；业务环境未重启、未切流。")


if __name__ == "__main__":
    main()
