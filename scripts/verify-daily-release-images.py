"""最终候选镜像隔离启动和离线归档；不挂载生产目录，不读取业务数据。"""
import hashlib,json,secrets,subprocess,time,tarfile
from pathlib import Path
ROOT=Path('/home/mayh/code/family-app')
DATA=json.loads((ROOT/'docs/daily-release-images.json').read_text())
IMAGES=DATA['images']
NAME='daily-image-check-'+secrets.token_hex(6)
created=[]
network=False
def run(args,ok=True):
 r=subprocess.run(args,capture_output=True,text=True)
 if ok and r.returncode: raise RuntimeError((r.stdout+r.stderr)[-1800:])
 return r
def start(role,image,env=None,aliases=()):
 target=NAME+'-'+role
 args=['docker','run','-d','--name',target,'--network',NAME,'--label','familyapp.image-check='+NAME]
 for alias in aliases: args+=['--network-alias',alias]
 for k,v in (env or {}).items(): args+=['-e',k+'='+v]
 args+=[image]
 run(args)
 created.append(target)
 return target
def probe(url,host=None):
 code="import urllib.request; r=urllib.request.Request("+repr(url)+",headers="+repr({'Host':host} if host else {})+"); urllib.request.urlopen(r,timeout=3).read()"
 return run(['docker','run','--rm','--network',NAME,'--entrypoint','python',IMAGES['daily-mcp']['image_id'],'-c',code],False).returncode==0
def wait(url,host=None):
 for n in range(60):
  if probe(url,host): return
  time.sleep(1)
 raise RuntimeError('镜像健康检查未通过：'+url)
evidence={'scope':'7个最终镜像启动/发现接口与静态代理验收，不替代完整业务及真实ChatGPT验收','images':{k:v['image_id'] for k,v in IMAGES.items()}}
try:
 for v in IMAGES.values():
  assert run(['docker','image','inspect','--format','{{.Id}}',v['tag']]).stdout.strip()==v['image_id']
 run(['docker','network','create','--internal','--label','familyapp.image-check='+NAME,NAME]);network=True
 pw=secrets.token_hex(32);secret=secrets.token_hex(32)
 pg=start('pg','postgres:16-alpine',{'POSTGRES_USER':'fixture','POSTGRES_DB':'fixture','POSTGRES_PASSWORD':pw})
 for n in range(45):
  if run(['docker','exec',pg,'pg_isready','-U','fixture'],False).returncode==0:break
  time.sleep(1)
 else:raise RuntimeError('测试PG未就绪')
 backend=start('backend',IMAGES['backend']['image_id'],{
 'SPRING_DATASOURCE_URL':'jdbc:postgresql://'+pg+':5432/fixture?stringtype=unspecified','SPRING_DATASOURCE_USERNAME':'fixture',
 'SPRING_DATASOURCE_PASSWORD':pw,'INTERNAL_API_SECRET':secret,'DAILY_ENABLED':'false','DAILY_REMOTE_ENABLED':'false',
 'AI_DECOMPOSE_ENABLED':'false','WECHAT_NOTIFY_ENABLED':'false','WECHAT_INTAKE_ENABLED':'false'},['backend'])
 wait('http://'+backend+':8080/actuator/health')
 jar=run(['docker','exec',backend,'sha256sum','/app/app.jar']).stdout.split()[0]
 assert jar==json.loads((ROOT/'docs/daily-candidate-build-verification.json').read_text())['backend_jar_sha256']
 run(['docker','exec',pg,'psql','-U','fixture','-d','fixture','-c','CREATE DATABASE work_fixture'])
 task=start('task',IMAGES['tp-task-service']['image_id'],{'DATABASE_URL':'postgresql+asyncpg://fixture:'+pw+'@'+pg+':5432/work_fixture',
 'INTERNAL_API_SECRET':secret,'BACKEND_BASE_URL':'http://'+backend+':8080'},['tp-task-service'])
 wait('http://'+task+':8100/healthz')
 agent=start('agent',IMAGES['tp-agent-service']['image_id'],{'INTERNAL_API_SECRET':secret,'BACKEND_BASE_URL':'http://'+backend+':8080',
 'MCP_TASK_SERVICE_URL':'http://'+task+':8100/mcp','LLM_BASE_URL':'http://no-llm-fixture:9999'},['tp-agent-service'])
 wait('http://'+agent+':8101/healthz')
 frontend=start('frontend',IMAGES['frontend']['image_id'],aliases=['frontend','wechat-service'])
 wait('http://'+frontend+'/')
 oauth=start('oauth',IMAGES['daily-oauth']['image_id'],{
 'DAILY_OAUTH_ENABLED':'true','SERVER_ADDRESS':'0.0.0.0','DAILY_OAUTH_DATABASE_URL':'jdbc:postgresql://'+pg+':5432/fixture?currentSchema=daily_oauth',
 'DAILY_OAUTH_DATABASE_USER':'fixture','DAILY_OAUTH_DATABASE_PASSWORD':pw,'SPRING_FLYWAY_SCHEMAS':'daily_oauth',
 'DAILY_OAUTH_CHATGPT_CLIENT_ID':'fixture-client','DAILY_OAUTH_CHATGPT_CLIENT_SECRET':secrets.token_hex(32),
 'DAILY_OAUTH_CHATGPT_REDIRECT_URI':'https://client.example.test/callback','DAILY_OAUTH_READER_ID':'fixture-reader',
 'DAILY_OAUTH_READER_SECRET':secrets.token_hex(32)})
 wait('http://'+oauth+':9010/daily-oauth/login')
 mcp=start('mcp',IMAGES['daily-mcp']['image_id'],{
 'DAILY_REMOTE_ENABLED':'true','DAILY_REMOTE_HOST':'0.0.0.0','DAILY_OAUTH_ISSUER':'https://app.example.test/daily-oauth',
 'DAILY_OAUTH_RESOURCE':'https://app.example.test/mcp/daily','DAILY_OAUTH_INTROSPECTION':'http://'+oauth+':9010/daily-oauth/oauth2/introspect',
 'DAILY_OAUTH_CLIENT_ID':'fixture-reader','DAILY_OAUTH_CLIENT_SECRET':secrets.token_hex(32),'DAILY_REMOTE_BACKEND':'http://'+backend+':8080',
 'INTERNAL_API_SECRET':secret})
 wait('http://'+mcp+':9011/.well-known/oauth-protected-resource/mcp/daily','app.example.test')
 nginx=start('nginx',IMAGES['nginx']['image_id'])
 run(['docker','exec',nginx,'nginx','-t'])
 wait('http://'+nginx+'/nginx-health')
 wait('http://'+nginx+'/')
 evidence['checks']=['backend健康及JAR摘要','工作任务/Agent健康','前端静态访问','OAuth登录页','MCP资源发现','网关配置/健康及静态代理']
 print('7个最终镜像隔离启动通过',flush=True)
finally:
 for target in reversed(created):
  labels=json.loads(run(['docker','inspect','--format','{{json .Config.Labels}}',target]).stdout)
  assert labels.get('familyapp.image-check')==NAME
  run(['docker','rm','-f','-v',target])
 if network:run(['docker','network','rm',NAME])
evidence['test_resources_removed']=True
folder=ROOT/'.volumes/daily-candidates/candidate-3c1sghxc/release-archives'
folder.mkdir(mode=0o700,exist_ok=True)
archive=folder/'candidate-images.tar'
assert not archive.exists(),'归档已存在，禁止覆盖'
run(['docker','save','-o',str(archive),*[v['image_id'] for v in IMAGES.values()]])
archive.chmod(0o600)
with tarfile.open(archive) as tar:
 manifest=json.load(tar.extractfile('manifest.json'))
 observed=set()
 for item in manifest:
  observed.add('sha256:'+hashlib.sha256(tar.extractfile(item['Config']).read()).hexdigest())
 index=json.load(tar.extractfile('index.json'))
 assert {i['digest'] for i in index['manifests']}=={v['image_id'] for v in IMAGES.values()}
 for member in tar.getmembers():
  if member.isfile() and member.name.startswith('blobs/sha256/'):
   assert hashlib.file_digest(tar.extractfile(member),'sha256').hexdigest()==member.name.rsplit('/',1)[1]
digest=hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()
evidence['archive']={'path':str(archive),'sha256':digest,'size':archive.stat().st_size,'image_configs_verified':len(observed),'docker_load_tested':False}
(ROOT/'docs/daily-release-image-verification.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+chr(10))
print(json.dumps(evidence,ensure_ascii=False))

