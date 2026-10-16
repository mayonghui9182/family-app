"""用户批准的第一阶段选择性发布；备份和配置只保存在本机受限目录。"""
import hashlib,json,os,shutil,subprocess,time
from datetime import datetime
from pathlib import Path
ROOT=Path('/home/mayh/code/family-app')
C=Path(json.loads((ROOT/'docs/daily-candidate-assembly.json').read_text())['directory'])
IMAGES=json.loads((ROOT/'docs/daily-release-images.json').read_text())['images']
SERVICES=['backend','tp-task-service','tp-agent-service','frontend','nginx']
WRITERS=['tp-agent-service','agent-service','wechat-service','backend','tp-task-service']
BACKUP=ROOT/'.volumes'/('daily-deploy-'+datetime.now().strftime('%Y%m%dT%H%M%S'))
def run(args,ok=True):
 r=subprocess.run(args,capture_output=True,text=True)
 if ok and r.returncode:raise RuntimeError('操作失败：'+args[0]+' '+args[1]+'；凭据及完整输出不回显')
 return r
def inspect(name):return json.loads(run(['docker','inspect',name]).stdout)[0]
def health(service):
 name='familyapp-'+service
 for n in range(90):
  c=inspect(name)
  if c['State'].get('Health',{}).get('Status')=='healthy':return
  if not c['State']['Running']:raise RuntimeError(service+'未运行')
  time.sleep(1)
 raise RuntimeError(service+'健康检查超时')
def sql(name,query):
 env=dict(x.split('=',1) for x in containers[name]['Config']['Env'])
 return run(['docker','exec',name,'psql','-X','-v','ON_ERROR_STOP=1','-U',env['POSTGRES_USER'],'-d',env['POSTGRES_DB'],'-Atc',query]).stdout.strip()
names=['familyapp-'+n for n in ('backend','frontend','nginx','tp-task-service','tp-agent-service','postgres','tp-task-db','agent-service','wechat-service','tool-service')]
containers={n:inspect(n) for n in names}
expected={'backend':'ad2ad7d0f1c4d434b8985b49bbb2c72b7c6d8175e09cc8809f8cd64b40a1d37f','frontend':'8759a5b7b6878a1c9a088b6d565772a05a4ea2489f244213a7c2c62d7b206edb','nginx':'6491c617208af5a5eeb22faf7d34bacd6ef1dfdc4b51831e5088422afea50d11','tp-task-service':'9229372633d3adf83a6ff74601cb4d492b674614f11a4b4d1aa6d72342841661','tp-agent-service':'ea0706c92bd33be25f29192e6b3df08f08f1206b007feb6098af3b640a10e3de'}
for s,image in expected.items():assert containers['familyapp-'+s]['Image']=='sha256:'+image,'生产镜像漂移：'+s
for c in containers.values():assert c['State']['Running'] and c['Config']['Labels']['com.docker.compose.project']=='family-app'
assert sql('familyapp-postgres',"SELECT max(version::int) FROM public.flyway_schema_history WHERE success AND type='SQL'")=='14'
assert sql('familyapp-tp-task-db','SELECT version_num FROM alembic_version')=='0002'
for name,volume in [('familyapp-postgres','family-app_postgres-data'),('familyapp-tp-task-db','family-app_tp-postgres-data')]:
 assert any(m.get('Name')==volume and m['Destination']=='/var/lib/postgresql/data' for m in containers[name]['Mounts'])
assembly=json.loads((ROOT/'docs/daily-candidate-assembly.json').read_text())
for item in assembly['files']:assert hashlib.sha256((C/item['path']).read_bytes()).hexdigest()==item['output_sha256']
for s in SERVICES:assert run(['docker','image','inspect','--format','{{.Id}}',IMAGES[s]['image_id']]).stdout.strip()==IMAGES[s]['image_id']
BACKUP.mkdir(mode=0o700)
snapshot=BACKUP/'containers.json';snapshot.write_text(json.dumps(containers));snapshot.chmod(0o600)
shutil.copy2(ROOT/'.env',BACKUP/'original.env');(BACKUP/'original.env').chmod(0o600)
flags=BACKUP/'flags.json'
def set_flags(enabled):
 flags.write_text(json.dumps({'services':{'backend':{'environment':{'DAILY_ENABLED':str(enabled).lower(),'DAILY_REMOTE_ENABLED':'false'}}}}));flags.chmod(0o600)
set_flags(False)
args=['docker','compose','--env-file',str(ROOT/'.env'),'--project-directory',str(C),'-p','family-app','-f',str(C/'docker-compose.yml'),'-f',str(C/'docker-compose.production.yml'),'-f',str(C/'release-config/images.yml'),'-f',str(flags)]
config=json.loads(run(args+['config','--format','json']).stdout)
secret=dict(x.split('=',1) for x in containers['familyapp-backend']['Config']['Env'])['INTERNAL_API_SECRET']
assert config['services']['backend']['environment']['INTERNAL_API_SECRET']==secret
for service in ('tp-task-service','tp-agent-service'):assert config['services'][service]['environment']['INTERNAL_API_SECRET']==secret
assert config['services']['backend']['environment']['DAILY_REMOTE_ENABLED']=='false'
assert all('build' not in s for s in config['services'].values())
changed=[];maintenance=False
evidence={'status':'发布执行中','backup_directory':str(BACKUP),'services':changed,'oauth':'关闭'}
try:
 run(['docker','stop','-t','30','familyapp-nginx']);maintenance=True
 run(['docker','stop','-t','30',*['familyapp-'+s for s in WRITERS]])
 print('入口和写入服务已暂停，开始双库备份',flush=True)
 backups={}
 for name,label in [('familyapp-postgres','main'),('familyapp-tp-task-db','work')]:
  env=dict(x.split('=',1) for x in containers[name]['Config']['Env'])
  path=BACKUP/(label+'.dump')
  with path.open('wb') as f:
   r=subprocess.run(['docker','exec',name,'pg_dump','-U',env['POSTGRES_USER'],'-d',env['POSTGRES_DB'],'-Fc'],stdout=f,stderr=subprocess.PIPE)
  assert r.returncode==0,'双库备份失败'
  path.chmod(0o600)
  with path.open('rb') as f:r=subprocess.run(['docker','exec','-i',name,'pg_restore','--list'],stdin=f,capture_output=True)
  assert r.returncode==0,'备份目录验证失败'
  with path.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
  backups[label]={'sha256':digest,'bytes':path.stat().st_size,'archive_list':'passed'}
 evidence['backups']=backups
 print('双库备份及归档目录校验通过',flush=True)
 for service in SERVICES[:-1]:
  run(args+['up','-d','--no-build','--no-deps',service]);changed.append(service);health(service)
  if service=='backend':
   assert sql('familyapp-postgres',"SELECT max(version::int) FROM public.flyway_schema_history WHERE success AND type='SQL'")=='14'
   assert sql('familyapp-postgres',"SELECT version FROM daily_app.flyway_schema_history WHERE type='SQL' AND success")=='1'
  if service=='tp-task-service':assert sql('familyapp-tp-task-db','SELECT version_num FROM alembic_version')=='0003'
  print(service+'更新及健康检查通过',flush=True)
 # 老服务沿用原容器，只恢复运行，不重新创建。
 run(['docker','start','familyapp-agent-service','familyapp-wechat-service'])
 health('agent-service');health('wechat-service')
 # 在重新开放网关前启用调度；个人设置仍默认关闭，远程读取关闭。
 set_flags(True)
 run(args+['up','-d','--no-build','--no-deps','backend']);health('backend')
 run(args+['up','-d','--no-build','--no-deps','nginx']);changed.append('nginx');health('nginx')
 maintenance=False
 for service in SERVICES:assert inspect('familyapp-'+service)['Image']==IMAGES[service]['image_id']
 assert inspect('familyapp-agent-service')['Id']==containers['familyapp-agent-service']['Id']
 assert inspect('familyapp-wechat-service')['Id']==containers['familyapp-wechat-service']['Id']
 for name in ('familyapp-postgres','familyapp-tp-task-db'):assert inspect(name)['Id']==containers[name]['Id']
 # 只验证无登录拒绝与静态路由，不请求私人记录。
 code="""import urllib.request,urllib.error,json,hashlib
 def get(path,headers=None):
  try:
   r=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1/'+path,headers=headers or {}),timeout=5)
  except urllib.error.HTTPError as e:r=e
  return r.status,r.read()
 status,body=get('');assert status==200
 status,body=get('api/daily/settings');assert status in (401,403) or json.loads(body).get('code')!=0
 status,body=get('api/work-tasks/tasks',{'X-User-Id':'1','X-Internal-Secret':'spoofed'});assert status in (401,403)
 print('静态入口及未认证/伪造身份拒绝通过')
 """
 # 去除多行嵌入代码的公共缩进。
 import textwrap
 code=code.replace(chr(10)+chr(32),chr(10))
 # 网关镜像无Python，利用原微信容器在同一网段请求网关。
 code=code.replace('http://127.0.0.1/','http://nginx/')
 run(['docker','exec','familyapp-wechat-service','python','-c',code])
 assert not run(['docker','ps','--filter','label=com.docker.compose.project=family-app','--filter','label=com.docker.compose.service=daily-oauth','-q']).stdout.strip()
 evidence.update({'status':'第一阶段已上线','main_version':'14','daily_version':'1','work_version':'0003','daily_enabled':True,'remote_enabled':False,'health':'五更新服务及两个原服务通过','unauthenticated_access':'拒绝','database_containers_unchanged':True})
 print('第一阶段上线和入口检查通过，OAuth保持关闭',flush=True)
except Exception as e:
 evidence['status']='发布未完成，需要核查';evidence['failure']=str(e);evidence['maintenance']=maintenance
 # 不自动回滚数据库或退回共享匿名工作版本。
 raise
finally:
 (BACKUP/'execution.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2));(BACKUP/'execution.json').chmod(0o600)
 public={k:v for k,v in evidence.items() if k!='backups'};public['backup_status']='本机双库备份，摘要保留受限目录'
 (ROOT/'docs/daily-production-release.json').write_text(json.dumps(public,ensure_ascii=False,indent=2)+chr(10))

