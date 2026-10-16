"""真实业务结构的双库夹具备份、恢复及升级；不连接现有数据库。"""
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
CANDIDATE = Path(json.loads((ROOT / 'docs/daily-candidate-assembly.json').read_text())['directory'])
assert CANDIDATE.resolve().is_relative_to(ROOT / '.volumes/daily-candidates')
NAME = 'daily-restore-' + secrets.token_hex(6)
PASSWORD = secrets.token_hex(24)


def run(args, *, content=None, env=None, ok=True):
    result = subprocess.run(args, input=content, capture_output=True, text=True, env=env)
    if ok and result.returncode:
        raise RuntimeError((result.stdout + result.stderr).replace(PASSWORD, '[夹具凭据]')[-2500:])
    return result


def sql(database, statement):
    return run(['docker', 'exec', NAME, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'tester',
                '-d', database, '-Atc', statement]).stdout.strip()


def fingerprint(database):
    # 仅对本脚本生成的虚构数据库做整库内容摘要，不输出行内容。
    tables = sql(database, "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
    rows = {}
    for table in tables:
        assert table.replace('_', '').isalnum()
        value = sql(database, f'SELECT coalesce(jsonb_agg(row ORDER BY row::text)::text,\'[]\') FROM (SELECT to_jsonb(t) row FROM public."{table}" t) x')
        rows[table] = hashlib.sha256(value.encode()).hexdigest()
    return rows


def main():
    created = False
    network = False
    evidence = {'scope': '原主V14/工作0002真实结构与虚构记录，备份恢复至新库后升级；非生产数据恢复验收'}
    archive_dir = CANDIDATE / 'restore-fixtures'
    archive_dir.mkdir(mode=0o700, exist_ok=True)
    try:
        run(['docker', 'network', 'create', '--label', 'familyapp.restore=' + NAME, NAME])
        network = True
        run(['docker', 'run', '-d', '--name', NAME, '--network', NAME, '--label', 'familyapp.restore=' + NAME,
             '-p', '127.0.0.1::5432', '-e', 'POSTGRES_USER=tester', '-e', 'POSTGRES_DB=fixture',
             '-e', 'POSTGRES_PASSWORD=' + PASSWORD, 'postgres:16-alpine'])
        created = True
        for _ in range(45):
            if run(['docker', 'exec', NAME, 'pg_isready', '-U', 'tester'], ok=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('夹具库启动超时')
        port = run(['docker', 'port', NAME, '5432/tcp']).stdout.strip()
        assert port.startswith('127.0.0.1:') and '\n' not in port
        for db in ('main_source', 'work_source', 'main_restore', 'work_restore'):
            sql('fixture', 'CREATE DATABASE ' + db)

        def alembic(database, version, ok=True):
            return run(['docker', 'run', '--rm', '--network', NAME, '--label', 'familyapp.restore=' + NAME,
                        '--entrypoint', 'alembic', '-w', '/workspace',
                        '-v', str(CANDIDATE / 'task-planner/task-service') + ':/workspace:ro',
                        '-e', f'DATABASE_URL=postgresql+asyncpg://tester:{PASSWORD}@{NAME}:5432/{database}',
                        'family-app-tp-task-service:latest', 'upgrade' if version != 'down' else 'downgrade',
                        version if version != 'down' else '0002'], ok=ok)

        with tempfile.TemporaryDirectory(prefix='candidate-restore-') as folder:
            temp = Path(folder)
            libs = temp / 'libs'
            libs.mkdir()
            build = json.loads((ROOT / 'docs/daily-candidate-build-verification.json').read_text())
            jar = CANDIDATE / build['backend_jar']
            assert hashlib.sha256(jar.read_bytes()).hexdigest() == build['backend_jar_sha256']
            with zipfile.ZipFile(jar) as archive:
                for item in archive.namelist():
                    if item.startswith('BOOT-INF/lib/') and item.endswith('.jar'):
                        (libs / Path(item).name).write_bytes(archive.read(item))
            source = temp / 'RestoreMigration.java'
            source.write_text('''import org.flywaydb.core.Flyway;
public class RestoreMigration {
 public static void main(String[] a) {
  Flyway.configure().dataSource(System.getenv("TEST_URL"), "tester", System.getenv("TEST_PASSWORD"))
   .schemas(a[1]).defaultSchema(a[1]).locations("filesystem:"+a[0])
   .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();
 }
}
''')
            run(['javac', '-cp', str(libs / '*'), str(source)])

            def migrate(database, daily=False):
                env = dict(os.environ, TEST_URL='jdbc:postgresql://' + port + '/' + database, TEST_PASSWORD=PASSWORD)
                path = CANDIDATE / 'backend/src/main/resources/db' / ('daily-migration' if daily else 'migration')
                run(['java', '-cp', str(temp) + ':' + str(libs / '*'), 'RestoreMigration',
                     str(path), 'daily_app' if daily else 'public'], env=env)

            migrate('main_source')
            sql('main_source', "INSERT INTO tasks(title,family_id,created_by,status) VALUES ('fixture-restore-task',1,1,'PENDING')")
            alembic('work_source', '0002')
            sql('work_source', "INSERT INTO categories(id,name,description) VALUES ('00000000-0000-0000-0000-000000000001','fixture-category','fixture-only')")
            before = {kind: fingerprint(kind + '_source') for kind in ('main', 'work')}
            dumps = {}
            for kind in ('main', 'work'):
                path = archive_dir / (kind + '.dump')
                with path.open('wb') as output:
                    result = subprocess.run(['docker', 'exec', NAME, 'pg_dump', '-U', 'tester', '-d', kind + '_source', '-Fc'], stdout=output, stderr=subprocess.PIPE)
                assert result.returncode == 0
                path.chmod(0o600)
                with path.open('rb') as data:
                    result = subprocess.run(['docker', 'exec', '-i', NAME, 'pg_restore', '-U', 'tester',
                                             '-d', kind + '_restore', '--no-owner', '--exit-on-error'], stdin=data, capture_output=True)
                assert result.returncode == 0, '夹具恢复失败'
                assert fingerprint(kind + '_restore') == before[kind]
                dumps[kind] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'tables_verified': len(before[kind])}
            migrate('main_restore')
            migrate('main_restore', daily=True)
            assert fingerprint('main_restore') == before['main']
            assert sql('main_restore', "SELECT max(version::int) FROM flyway_schema_history WHERE type='SQL'") == '14'
            assert sql('main_restore', "SELECT version FROM daily_app.flyway_schema_history WHERE type='SQL'") == '1'
            # 序列恢复后必须能继续自动分配主键。
            sql('main_restore', "INSERT INTO tasks(title,family_id,created_by,status) VALUES ('fixture-after-restore',1,1,'PENDING')")
            sql('main_restore', "INSERT INTO daily_app.daily_records(user_id,plan_date,kind,content,generated_at) VALUES (1,CURRENT_DATE,'PLAN','fixture-only',now())")
            alembic('work_restore', 'head')
            assert sql('work_restore', 'SELECT version_num FROM alembic_version') == '0003'
            assert sql('work_restore', "SELECT user_id FROM categories WHERE name='fixture-category'") == 'default'
            sql('work_restore', "INSERT INTO categories(id,name,user_id) VALUES ('00000000-0000-0000-0000-000000000002','fixture-category','1'),('00000000-0000-0000-0000-000000000003','fixture-category','2')")
            assert alembic('work_restore', 'down', ok=False).returncode != 0
            assert sql('work_restore', 'SELECT version_num FROM alembic_version') == '0003'
            assert sql('work_restore', "SELECT count(*) FROM categories WHERE name='fixture-category'") == '3'
            evidence.update({'backups': dumps, 'both_restored_contents_match': True,
                             'main_after_upgrade': 'V14 + daily_app V1', 'main_sequence_write': 'passed',
                             'work_after_upgrade': '0003', 'legacy_owner': 'default',
                             'two_users_same_category_name': 'passed', 'unsafe_owner_downgrade': 'rejected'})
    finally:
        if created:
            labels = json.loads(run(['docker', 'inspect', '--format', '{{json .Config.Labels}}', NAME]).stdout)
            assert labels.get('familyapp.restore') == NAME
            run(['docker', 'rm', '-f', '-v', NAME])
        if network:
            labels = json.loads(run(['docker', 'network', 'inspect', '--format', '{{json .Labels}}', NAME]).stdout)
            assert labels.get('familyapp.restore') == NAME
            run(['docker', 'network', 'rm', NAME])
    evidence['test_resources_removed'] = True
    (ROOT / 'docs/daily-candidate-restore-verification.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == '__main__':
    main()
