"""将独立候选输入范围展开为文件摘要清单，不复制或部署任何文件。"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TREES = ['backend/src', 'backend/gradle', 'frontend/src', 'frontend/public',
         'task-planner/task-service/src', 'task-planner/task-service/alembic',
         'task-planner/task-service/tests', 'task-planner/agent-service/src',
         'task-planner/agent-service/tests', 'integrations/daily-oauth/src',
         'integrations/daily-mcp/tests']
FILES = ['backend/build.gradle', 'backend/settings.gradle', 'backend/gradlew',
         'backend/gradlew.bat', 'backend/Dockerfile', 'backend/.dockerignore',
         'frontend/package.json', 'frontend/package-lock.json', 'frontend/vite.config.js',
         'frontend/index.html', 'frontend/Dockerfile', 'frontend/nginx-spa.conf',
         'docker-compose.yml', 'docker-compose.production.yml', 'docker-compose.tls.yml',
         'docker-compose.daily-oauth.yml', 'nginx/Dockerfile', 'nginx/default.conf',
         'nginx/https.conf', 'nginx/daily-oauth-routes.conf',
         'integrations/daily-oauth/build.gradle', 'integrations/daily-oauth/settings.gradle',
         'integrations/daily-oauth/Dockerfile', 'integrations/daily-mcp/Dockerfile',
         'integrations/daily-mcp/requirements.txt', 'integrations/daily-mcp/daily_mcp.py',
         'integrations/daily-mcp/remote_mcp.py']
for service in ['task-service', 'agent-service']:
    for filename in ['pyproject.toml', 'Dockerfile', '.dockerignore', 'docker-entrypoint.sh', 'alembic.ini']:
        path = 'task-planner/' + service + '/' + filename
        if (ROOT / path).exists(): FILES.append(path)

def decision(path):
    """转换项必须在装配时执行并验收，不能直接复制后宣称候选完整。"""
    if '/wechat/conversation/' in path or 'WechatConversation' in Path(path).name:
        return 'exclude', '排除新微信会话及专项测试'
    if '/db/' in path and Path(path).name.startswith(('V15__', 'V16__')):
        return 'exclude', '排除未发布微信结构'
    if '/db/' in path and Path(path).name.startswith('V17__'):
        return 'migration_design', '由4.2确定独立历史，禁止原样跳版本'
    if path.endswith('FamilyService.java'):
        return 'transform', '移除WechatFamilyDeletionService及routes；保留生产家庭锁和空家庭删除'
    if path.endswith('WechatBindRepository.java'):
        return 'transform', '移除会话专用findByIdForUpdate及对应注解导入'
    if path.endswith('WechatNotifyClient.java'):
        return 'transform', '移除sendKefuTextDetailed，保留旧通知API'
    if path.endswith('TaskCreationCommandService.java'):
        return 'transform', '保留通用创建校验；移除createPendingAgentBatch及仅供其使用的分支'
    if path in ['backend/src/main/resources/application.yml', 'docker-compose.yml']:
        return 'transform', '移除新会话配置，保留既有配置与每日工作依赖；既有未改服务不盲目重建'
    if '/src/test/' in path and any(token in (ROOT / path).read_bytes()
                                  for token in [b'wechat.conversation', b'createPendingAgentBatch']):
        return 'test_review', '混合测试需拆除会话依赖，保留旧业务回归'
    return 'include', '候选源输入；仍需4.3独立构建及4.4回归'

paths = set(FILES)
for tree in TREES:
    base = ROOT / tree
    if base.exists():
        paths.update(p.relative_to(ROOT).as_posix() for p in base.rglob('*')
                     if p.is_file() and not p.is_symlink()
                     and not any(part.startswith('.') or part == '__pycache__' for part in p.relative_to(base).parts))
items = []
for path in sorted(paths):
    file = ROOT / path
    assert file.is_file() and not file.is_symlink(), path
    assert file.resolve().is_relative_to(ROOT.resolve()), path
    action, reason = decision(path)
    items.append({'path': path, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                  'action': action, 'reason': reason})
report = {'status': '输入清单，非已装配发布包；transform/migration_design/test_review不得直接复制放行',
          'source_policy': '当前已审查工作树；既有产物对应关系见两份基线报告，前端dist不证明源码同源',
          'excluded_runtime_services': ['agent-service', 'wechat-service', 'tool-service'],
          'excluded_runtime_service_policy': '本次不重建；发布前核验现有镜像可恢复及接口兼容',
          'files': items}
(ROOT / 'docs/daily-release-source-manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({action: sum(x['action'] == action for x in items)
                  for action in sorted({x['action'] for x in items})}, ensure_ascii=False))
