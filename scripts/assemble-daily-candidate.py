"""按摘要清单装配新候选目录；不修改原应用、不复制环境或运行数据、不部署。"""
import hashlib
import json
from pathlib import Path
import re
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text, old, new):
    assert text.count(old) == 1, old
    return text.replace(old, new)


def cut(text, start, end):
    assert text.count(start) == 1 and text.count(end) == 1, start
    left, right = text.index(start), text.index(end)
    assert left < right
    return text[:left] + text[right:]


def transform(path, data):
    text = data.decode().replace('\r\n', '\n')
    if path.endswith('FamilyService.java'):
        text = re.sub(r'^.*(?:import com.familyapp.wechat.conversation|private final WechatFamilyDeletionService|this.wechatDeletion =|List<WechatChatContext> routes =|wechatDeletion.softDelete|wechatDeletion.physicalDelete).*\n', '', text, flags=re.M)
        text = replace_once(text, 'JdbcTemplate jdbcTemplate,\n                         WechatFamilyDeletionService wechatDeletion)', 'JdbcTemplate jdbcTemplate)')
        text = text.replace('deleteEmptyFamily(family, routes)', 'deleteEmptyFamily(family)')
        text = text.replace('deleteEmptyFamily(Family family, List<WechatChatContext> routes)', 'deleteEmptyFamily(Family family)')
    elif path.endswith('WechatBindRepository.java'):
        text = cut(text, '    /** 创建绑定级会话上下文', '    boolean existsByOpenid')
        text = re.sub(r'^import (?:jakarta.persistence.LockModeType|org.springframework.data.jpa.repository.(?:Lock|Query)|org.springframework.data.repository.query.Param);\n', '', text, flags=re.M)
    elif path.endswith('WechatNotifyClient.java'):
        text = cut(text, '    /** 持久化 outbox 使用逐条错误码', '    /**\n     * 批量发送模板消息')
    elif path.endswith('TaskCreationCommandService.java'):
        text = cut(text, '    /** 微信确认批次固定', '    private List<Task> createBatch')
        text = text.replace('source, requests, false)', 'source, requests)')
        text = text.replace('List<CreateTaskRequest> requests,\n                                  boolean confirmedCandidate)', 'List<CreateTaskRequest> requests)')
        text = text.replace('request, confirmedCandidate)', 'request)')
        text = text.replace('CreateTaskRequest input,\n                            boolean confirmedCandidate)', 'CreateTaskRequest input)')
        text = cut(text, '        if (confirmedCandidate', '        if (status == TaskStatus.CANCELLED')
    elif path.endswith('TaskCreationBatchIntegrationTest.java'):
        text = cut(text, '    @Test\n    void candidateCannotOverridePendingOrAgent()', '    @Test\n    void childDefaultsDoNotMutateCandidateInputs()')
        text = text.replace('createPendingAgentBatch(', 'create(')
        text = text.replace('1L, 1L, List.of(', '1L, 1L, TaskSource.AGENT, List.of(')
    elif path == 'backend/src/main/resources/application.yml':
        text = cut(text, '  # ===== 微信持续会话入口', 'daily:\n')
    elif path == 'docker-compose.yml':
        text = re.sub(r'^.*WECHAT_CONVERSATION_.*\n', '', text, flags=re.M)
    else:
        raise AssertionError('未实现的转换：' + path)
    assert 'wechat.conversation' not in text and 'confirmedCandidate' not in text
    return text.encode()


def main():
    manifest = ROOT / 'docs/daily-release-source-manifest.json'
    items = json.loads(manifest.read_text())['files']
    # 先验证全部输入；摘要漂移时停止，不隐式刷新批准范围。
    for item in items:
        source = ROOT / item['path']
        assert source.resolve().is_relative_to(ROOT) and not source.is_symlink()
        assert hashlib.sha256(source.read_bytes()).hexdigest() == item['sha256'], item['path']
    parent = ROOT / '.volumes/daily-candidates'
    parent.mkdir(parents=True, exist_ok=True)
    dest = Path(tempfile.mkdtemp(prefix='candidate-', dir=parent))
    output = []

    def write(path, data, origin, input_hash):
        target = dest / path
        assert target.resolve().is_relative_to(dest)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        if path.endswith('/gradlew') or path.endswith('/docker-entrypoint.sh'):
            target.chmod(0o755)
        output.append({'path': path, 'origin': origin, 'input_sha256': input_hash,
                       'output_sha256': hashlib.sha256(data).hexdigest()})

    for item in items:
        path, action = item['path'], item['action']
        if action in ('exclude', 'migration_design'):
            continue
        data = (ROOT / path).read_bytes()
        if action in ('transform', 'test_review'):
            data = transform(path, data)
        else:
            assert action == 'include'
        if '/com/familyapp/daily/' in path and path.endswith('.java'):
            text = data.decode()
            text = re.sub(r'\bdaily_(records|settings)\b', r'daily_app.daily_\1', text)
            text = text.replace('db/migration/V17__Personal_Daily_Planning.sql', 'db/daily-migration/V1__Personal_Daily_Planning.sql')
            text = text.replace('db/rollback/V17__Personal_Daily_Planning.sql', 'db/daily-rollback/rollback-empty.sql')
            if 'new ResourceDatabasePopulator(' in text:
                jdbc = 'jdbc' if path.endswith('DailyApiTest.java') else 'db'
                text = text.replace('        new ResourceDatabasePopulator(', f'        {jdbc}.execute("CREATE SCHEMA IF NOT EXISTS daily_app");\n        new ResourceDatabasePopulator(')
                text = text.replace(f'{jdbc}.execute("DROP TABLE IF EXISTS daily_app.daily_records")',
                                    f'{jdbc}.execute("CREATE SCHEMA IF NOT EXISTS daily_app");{jdbc}.execute("DROP TABLE IF EXISTS daily_app.daily_records")')
            if path.endswith('DailyPostgresTest.java'):
                text = text.replace('        flyway.migrate();', '        flyway.migrate();\n        Flyway.configure().dataSource(url,user,password).schemas("daily_app").defaultSchema("daily_app")\n                .locations("classpath:db/daily-migration").load().migrate();')
                text = text.replace("table_schema='public' AND table_name='daily_app.daily_records'", "table_schema='daily_app' AND table_name='daily_records'")
            data = text.encode()
        write(path, data, path, item['sha256'])
    for name, target in [('V1__Personal_Daily_Planning.sql', 'db/daily-migration/V1__Personal_Daily_Planning.sql'),
                         ('rollback-empty.sql', 'db/daily-rollback/rollback-empty.sql')]:
        path = 'integrations/daily-migrations/' + name
        data = (ROOT / path).read_bytes()
        write('backend/src/main/resources/' + target, data, path, hashlib.sha256(data).hexdigest())
    config = '''package com.familyapp.daily;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 先完成主库迁移，再建立独立每日历史；任一步失败均阻止启动。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class DailyMigrationConfiguration {
    @Bean
    public FlywayMigrationStrategy dailyMigrationStrategy() {
        return main -> {
            main.migrate();
            Flyway.configure().dataSource(main.getConfiguration().getDataSource())
                    .locations("classpath:db/daily-migration")
                    .schemas("daily_app").defaultSchema("daily_app")
                    .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true)
                    .load().migrate();
        };
    }
}
'''
    write('backend/src/main/java/com/familyapp/daily/DailyMigrationConfiguration.java', config.encode(),
          'scripts/assemble-daily-candidate.py', None)
    forbidden = ('wechat.conversation', 'createPendingAgentBatch', 'confirmedCandidate')
    for file in (dest / 'backend/src').rglob('*.java'):
        text = file.read_text()
        assert not any(word in text for word in forbidden), file
    report = {'status': '已装配，构建/回归与部署材料另外验收', 'directory': str(dest),
              'input_manifest_sha256': hashlib.sha256(manifest.read_bytes()).hexdigest(),
              'assembler_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'files': output}
    (dest / 'candidate-manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    (ROOT / 'docs/daily-candidate-assembly.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'directory': str(dest), 'files': len(output)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
