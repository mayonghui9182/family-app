"""使用原运行迁移和随机隔离 PostgreSQL 验证每日历史，不读取业务库。"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / '.volumes/backend-recovery-20261011/app.jar'
EXPECTED = '3d651317a83e2d34ab9ade806e5d4ba5369fda49f161052e5a06435754780e29'
NAME = 'daily-history-' + secrets.token_hex(6)
LABEL = 'familyapp.daily-history-check'
PASSWORD = secrets.token_hex(24)


def run(args, *, content=None, env=None, ok=True):
    result = subprocess.run(args, input=content, text=True, capture_output=True, env=env)
    if ok and result.returncode:
        raise RuntimeError((result.stdout + result.stderr).replace(PASSWORD, '[测试凭据]')[-4000:])
    return result


def sql(query, *, ok=True):
    return run(['docker', 'exec', '-i', NAME, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                '-U', 'fixture', '-d', 'fixture', '-At'], content=query, ok=ok)


def expect(query, value):
    actual = sql(query).stdout.strip()
    assert actual == value, (query, actual, value)


def main():
    assert hashlib.sha256(JAR.read_bytes()).hexdigest() == EXPECTED
    checks = []
    created = False
    evidence = {'scope': '随机隔离 PostgreSQL 与虚构数据；非生产升级或业务恢复验收',
                'runtime_jar_sha256': EXPECTED, 'checks': checks}
    try:
        with tempfile.TemporaryDirectory(prefix='familyapp-daily-history-') as folder:
            temp = Path(folder)
            libs, migrations = temp / 'libs', temp / 'main'
            libs.mkdir()
            migrations.mkdir()
            digests = {}
            with zipfile.ZipFile(JAR) as jar:
                for item in jar.namelist():
                    if item.startswith('BOOT-INF/lib/') and item.endswith('.jar'):
                        (libs / Path(item).name).write_bytes(jar.read(item))
                    if item.startswith('BOOT-INF/classes/db/migration/') and item.endswith('.sql'):
                        data = jar.read(item)
                        (migrations / Path(item).name).write_bytes(data)
                        digests[Path(item).name] = hashlib.sha256(data).hexdigest()
            # 用运行程序自带的 Flyway 和驱动，避免测试工具版本漂移。
            source = temp / 'MigrationProbe.java'
            source.write_text('''
import org.flywaydb.core.Flyway;
public class MigrationProbe {
    public static void main(String[] args) {
        Flyway.configure().dataSource(System.getenv("FIXTURE_URL"), "fixture", System.getenv("FIXTURE_PASSWORD"))
            .locations("filesystem:" + args[0]).schemas(args[1]).defaultSchema(args[1])
            .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();
    }
}
''')
            run(['javac', '-cp', str(libs / '*'), str(source)])
            run(['docker', 'run', '-d', '--name', NAME, '--label', LABEL + '=' + NAME,
                 '-p', '127.0.0.1::5432', '-e', 'POSTGRES_USER=fixture',
                 '-e', 'POSTGRES_DB=fixture', '-e', 'POSTGRES_PASSWORD=' + PASSWORD,
                 'postgres:16-alpine'])
            created = True
            for _ in range(60):
                if run(['docker', 'exec', NAME, 'pg_isready', '-U', 'fixture'], ok=False).returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('隔离数据库启动超时')
            port = run(['docker', 'port', NAME, '5432/tcp']).stdout.strip()
            assert port.startswith('127.0.0.1:') and '\n' not in port
            env = dict(os.environ, FIXTURE_URL='jdbc:postgresql://' + port + '/fixture',
                       FIXTURE_PASSWORD=PASSWORD)

            def migrate(path, schema):
                run(['java', '-cp', str(temp) + ':' + str(libs / '*'),
                     'MigrationProbe', str(path), schema], env=env)

            def history(schema):
                return sql(f"SELECT version,checksum,success FROM {schema}.flyway_schema_history WHERE type='SQL' ORDER BY installed_rank").stdout

            daily = temp / 'daily'
            daily.mkdir()
            migration = ROOT / 'integrations/daily-migrations/V1__Personal_Daily_Planning.sql'
            (daily / migration.name).write_bytes(migration.read_bytes())
            evidence['daily_sql_sha256'] = hashlib.sha256(migration.read_bytes()).hexdigest()
            evidence['main_sql_sha256'] = digests
            migrate(migrations, 'public')
            base = history('public')
            expect("SELECT max(version::int) FROM public.flyway_schema_history WHERE type='SQL'", '14')
            migrate(daily, 'daily_app')
            assert base == history('public')
            expect("SELECT version FROM daily_app.flyway_schema_history WHERE type='SQL'", '1')
            expect("SELECT to_regclass('public.daily_records') IS NULL", 't')
            checks.append('V14 → 独立 daily_app V1，主历史原样保留且 public 无每日表')
            migrate(migrations, 'public')
            migrate(daily, 'daily_app')
            assert base == history('public')
            checks.append('两条迁移链重复执行幂等')
            sql('INSERT INTO daily_app.daily_settings(user_id,enabled) SELECT id,true FROM public.users ORDER BY id LIMIT 1;')
            expect('SELECT count(*) FROM daily_app.daily_settings', '1')
            insert = "INSERT INTO daily_app.daily_records(user_id,plan_date,kind,content,generated_at) SELECT user_id,DATE '2026-10-13','PLAN','fixture-only',now() FROM daily_app.daily_settings;"
            sql(insert)
            assert sql(insert, ok=False).returncode != 0
            assert sql('INSERT INTO daily_app.daily_settings(user_id) VALUES (-99999)', ok=False).returncode != 0
            expect('WITH changed AS (UPDATE daily_app.daily_records SET version=version+1 WHERE version=0 RETURNING id) SELECT count(*) FROM changed', '1')
            expect('WITH changed AS (UPDATE daily_app.daily_records SET version=version+1 WHERE version=0 RETURNING id) SELECT count(*) FROM changed', '0')
            expect("SELECT has_schema_privilege('agent_reader','daily_app','USAGE')", 'f')
            checks.append('唯一约束、账号外键、乐观版本更新及 agent_reader 无命名空间访问权')
            rollback = (ROOT / 'integrations/daily-migrations/rollback-empty.sql').read_text()
            assert sql(rollback, ok=False).returncode != 0
            expect('SELECT count(*) FROM daily_app.daily_records', '1')
            checks.append('已有虚构记录时回退拒绝，记录保留')
            for version in (15, 16):
                paths = list((ROOT / 'backend/src/main/resources/db/migration').glob(f'V{version}__*.sql'))
                assert len(paths) == 1
                data = paths[0].read_bytes()
                (migrations / paths[0].name).write_bytes(data)
                digests[paths[0].name] = hashlib.sha256(data).hexdigest()
            migrate(migrations, 'public')
            expect("SELECT max(version::int) FROM public.flyway_schema_history WHERE type='SQL'", '16')
            assert history('public').startswith(base)
            expect("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='wechat_chat_context' AND column_name='default_family_id'", '1')
            expect('SELECT content FROM daily_app.daily_records', 'fixture-only')
            checks.append('后续 V15/V16 正常执行，V1—V14 校验值及每日记录保留')
            future = history('public')
            sql('DELETE FROM daily_app.daily_records;')
            assert sql(rollback, ok=False).returncode != 0
            checks.append('仅有个人设置时同样拒绝删表')
            sql('DELETE FROM daily_app.daily_settings;')
            sql(rollback)
            expect("SELECT to_regnamespace('daily_app') IS NULL", 't')
            assert history('public') == future
            migrate(daily, 'daily_app')
            migrate(migrations, 'public')
            assert history('public') == future
            expect('SELECT count(*) FROM daily_app.daily_records', '0')
            checks.append('空每日结构受限回退后在主库 V16 重建，不修改主历史')
            evidence['postgres_image'] = run(['docker', 'inspect', '--format', '{{.Image}}', NAME]).stdout.strip()
    finally:
        if created:
            labels = json.loads(run(['docker', 'inspect', '--format', '{{json .Config.Labels}}', NAME]).stdout)
            assert labels.get(LABEL) == NAME
            run(['docker', 'rm', '-f', '-v', NAME])
    evidence['test_container_removed'] = True
    (ROOT / 'docs/daily-migration-history-verification.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'passed': checks, 'test_container_removed': True}, ensure_ascii=False))


if __name__ == '__main__':
    main()
