"""核验候选源摘要、测试报告与构建产物，避免把旧工作树测试算作候选测试。"""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
assembly = json.loads((ROOT / 'docs/daily-candidate-assembly.json').read_text())
candidate = Path(assembly['directory']).resolve()
assert candidate.is_relative_to(ROOT / '.volumes/daily-candidates')
for item in assembly['files']:
    assert hashlib.sha256((candidate / item['path']).read_bytes()).hexdigest() == item['output_sha256'], item['path']
    if item['input_sha256']:
        assert hashlib.sha256((ROOT / item['origin']).read_bytes()).hexdigest() == item['input_sha256'], item['origin']
assert hashlib.sha256((ROOT / 'scripts/assemble-daily-candidate.py').read_bytes()).hexdigest() == assembly['assembler_sha256']


def tests(folder):
    result = dict(tests=0, skipped=0, failures=0, errors=0)
    reports = list(folder.glob('TEST-*.xml'))
    assert reports
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in result:
            result[key] += int(suite.attrib.get(key, 0))
    assert result['failures'] == 0 and result['errors'] == 0
    return result


jar = candidate / 'backend/build/libs/family-app-backend-0.1.0.jar'
if not jar.exists():
    jars = [p for p in (candidate / 'backend/build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
    assert len(jars) == 1
    jar = jars[0]
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert not any('/wechat/conversation/' in name for name in names)
    assert not any('/db/migration/V' + str(v) + '__' in name for name in names for v in (15, 16, 17))
    assert 'BOOT-INF/classes/db/daily-migration/V1__Personal_Daily_Planning.sql' in names
    assert 'BOOT-INF/classes/com/familyapp/daily/DailyMigrationConfiguration.class' in names
frontend_log = (candidate / 'frontend-build.log').read_text()
assert '144 passed' in frontend_log and 'built in' in frontend_log
assets = {p.relative_to(candidate).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in (candidate / 'frontend/dist').rglob('*') if p.is_file()}
assert assets
evidence = {
    'scope': '4.3 源装配与本机构建；不含生产发布、候选真实PG启动/双库恢复/跨服务回归',
    'candidate_directory': str(candidate), 'source_files_verified': len(assembly['files']),
    'backend': tests(candidate / 'backend/build/test-results/test'),
    'oauth': tests(candidate / 'integrations/daily-oauth/build/test-results/test'),
    'frontend': {'passed': 144, 'files_passed': 34, 'build': 'passed'},
    'backend_jar': jar.relative_to(candidate).as_posix(),
    'backend_jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
    'frontend_assets_sha256': assets,
    'jar_excludes_wechat_conversation_and_main_v15_v16_v17': True,
    'jar_contains_daily_schema_migration_and_strategy': True,
}
(ROOT / 'docs/daily-candidate-build-verification.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: v for k, v in evidence.items() if k != 'frontend_assets_sha256'}, ensure_ascii=False))
