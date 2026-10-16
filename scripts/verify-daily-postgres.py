"""独立 PostgreSQL 验证每日记录；不检查或连接业务容器。"""

import json
import os
import secrets
import subprocess
import time
from pathlib import Path


def main():
    """随机容器仅发布回环端口，结束核对标签后删除本轮资源。"""
    token=secrets.token_hex(6)
    name="familyapp-daily-test-"+token
    database="familyapp_daily_"+token+"_test"
    password=secrets.token_urlsafe(24)
    try:
        result=subprocess.run(["docker","run","-d","--name",name,"--label","familyapp.test.run="+name,
            "-p","127.0.0.1::5432","-e","POSTGRES_USER=tester","-e","POSTGRES_PASSWORD="+password,
            "-e","POSTGRES_DB="+database,"postgres:16-alpine"],capture_output=True,text=True,check=False)
        if result.returncode:raise RuntimeError("测试 PostgreSQL 创建失败")
        for _ in range(30):
            ready=subprocess.run(["docker","exec",name,"pg_isready","-U","tester"],capture_output=True,check=False)
            if ready.returncode==0:break
            time.sleep(1)
        else:raise RuntimeError("测试 PostgreSQL 未就绪")
        inspect=subprocess.run(["docker","inspect","--format","{{json .NetworkSettings.Ports}}",name],
            capture_output=True,text=True,check=True)
        port=json.loads(inspect.stdout)["5432/tcp"][0]["HostPort"]
        env=os.environ.copy()
        env.update({"FAMILYAPP_DAILY_POSTGRES_URL":f"jdbc:postgresql://127.0.0.1:{port}/{database}",
                    "FAMILYAPP_DAILY_POSTGRES_USER":"tester","FAMILYAPP_DAILY_POSTGRES_PASSWORD":password})
        run=subprocess.run(["./gradlew","test","--tests","com.familyapp.daily.DailyPostgresTest","--no-daemon"],
            cwd=Path(__file__).resolve().parent.parent/"backend",env=env,check=False)
        if run.returncode:raise RuntimeError("每日 PostgreSQL 验证失败")
    finally:
        check=subprocess.run(["docker","inspect","--format",'{{index .Config.Labels "familyapp.test.run"}}',name],
                             capture_output=True,text=True,check=False)
        if check.returncode==0 and check.stdout.strip()==name:
            subprocess.run(["docker","rm","-f",name],stdout=subprocess.DEVNULL,check=False)


if __name__=="__main__":main()
