"""只提取固定程序路径，准备缺失旧镜像的恢复候选；不读取环境、日志或业务数据。"""
import hashlib,io,json,secrets,subprocess,tarfile,time
from pathlib import Path,PurePosixPath
ROOT=Path('/home/mayh/code/family-app')
OUT=ROOT/'.volumes/runtime-recovery-20261014'
OUT.mkdir(mode=0o700,exist_ok=True)
def run(args,ok=True):
 r=subprocess.run(args,capture_output=True,text=True)
 if ok and r.returncode:raise RuntimeError('恢复准备失败：'+args[0]+' '+args[1])
 return r
def versions(args):
 code="import importlib.metadata as m,json; print(json.dumps({d.metadata['Name'].lower():d.version for d in m.distributions()},sort_keys=True))"
 return json.loads(run(args+['python','-c',code]).stdout)
def programs(container,path,target):
 r=subprocess.run(['docker','cp',container+':'+path,'-'],capture_output=True)
 assert r.returncode==0
 files={}
 with tarfile.open(fileobj=io.BytesIO(r.stdout)) as t:
  for member in t.getmembers():
   parts=PurePosixPath(member.name).parts
   assert '..' not in parts and not member.name.startswith('/')
   if not member.isfile():continue
   relative=PurePosixPath(*parts[1:]) if len(parts)>1 else PurePosixPath(parts[0])
   if relative.suffix not in ('.py','.json','.j2'):continue
   data=t.extractfile(member).read()
   p=target/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
   files[str(relative)]=hashlib.sha256(data).hexdigest()
 assert files
 return files
report={'scope':'程序恢复候选，非原镜像完整复原；未复制环境或业务数据','services':{}}
for service,base in [
 ('agent-service','sha256:83eefccf875c7d016706252775b8885d692c4a380bb351497a0ef3691f6d91b9'),
 ('wechat-service','sha256:17ee80bd1b484bb3fa93d21bc07e6a23b225436aaf9d55e6fab11d1b6e084f5d')]:
 container='familyapp-'+service
 context=OUT/service
 context.mkdir(exist_ok=True)
 original=versions(['docker','exec',container])
 deps=versions(['docker','run','--rm','--network','none','--entrypoint','',base])
 changed={k for k in set(original)|set(deps) if original.get(k)!=deps.get(k)}
 if changed:
  dependencies=context/'dependencies';dependencies.mkdir(exist_ok=True)
  for package in sorted(changed):
   assert package in original and package in deps
   code="import importlib.metadata as m,json;d=m.distribution("+repr(package)+");print(json.dumps({'metadata':str(d._path),'files':[str(f) for f in d.files]}))"
   detail=json.loads(run(['docker','exec',container,'python','-c',code]).stdout)
   prefix='/usr/local/lib/python3.12/site-packages/'
   assert detail['metadata'].startswith(prefix)
   roots={PurePosixPath(f).parts[0] for f in detail['files'] if not f.startswith('../')}
   for root in roots:
    assert '/' not in root and root not in ('.','..')
    data=subprocess.run(['docker','cp',container+':'+prefix+root,'-'],capture_output=True)
    assert data.returncode==0
    with tarfile.open(fileobj=io.BytesIO(data.stdout)) as archive:
     for member in archive.getmembers():
      parts=PurePosixPath(member.name).parts
      assert '..' not in parts and not member.name.startswith('/')
      if not member.isfile() or '__pycache__' in parts or member.name.endswith('.pyc'):continue
      assert not any(part=='.env' for part in parts)
      target=dependencies/PurePosixPath(member.name)
      target.parent.mkdir(parents=True,exist_ok=True)
      target.write_bytes(archive.extractfile(member).read())

 files=programs(container,'/srv/'+service+'/app',context/'app')
 localbase='familyapp-recovery-base/'+service+':20261014'
 run(['docker','tag',base,localbase])
 dockerfile='FROM '+localbase+chr(10)+'USER root'+chr(10)+'RUN rm -rf /srv/'+service+'/app'+chr(10)+'COPY app/ /srv/'+service+'/app/'+chr(10)+'USER appuser'+chr(10)
 if changed:
  removed=[]
  for package in sorted(changed):
   code="import importlib.metadata as m,json;d=m.distribution("+repr(package)+");print(json.dumps({'metadata':str(d._path),'files':[str(f) for f in d.files]}))"
   detail=json.loads(run(['docker','run','--rm','--network','none','--entrypoint','',base,'python','-c',code]).stdout)
   roots={PurePosixPath(f).parts[0] for f in detail['files'] if not f.startswith('../')}
   for name in roots:
    assert '/' not in name and name not in ('.','..')
    removed.append('/usr/local/lib/python3.12/site-packages/'+name)
  dockerfile=dockerfile.replace('USER appuser'+chr(10),'RUN rm -rf '+' '.join(removed)+chr(10)+'COPY dependencies/ /usr/local/lib/python3.12/site-packages/'+chr(10)+'USER appuser'+chr(10))
 (context/'Dockerfile').write_text(dockerfile)
 tag='familyapp-recovery/'+service+':20261014'
 run(['docker','build','--network=none','--pull=false','-t',tag,str(context)])
 assert versions(['docker','run','--rm','--network','none','--entrypoint','',tag])==original
 name='recovery-check-'+secrets.token_hex(6)
 port=8700 if service=='agent-service' else 8900
 run(['docker','run','-d','--name',name,'--network','none','--label','familyapp.recovery-check='+name,
      '-e','INTERNAL_API_SECRET='+secrets.token_hex(32),'-e','DB_AGENT_READER_URL=postgresql+psycopg://fixture:fixture@127.0.0.1:5432/fixture','-e','DEEPSEEK_API_KEY=fixture-not-real',tag])
 try:
  for n in range(40):
   code="import urllib.request; urllib.request.urlopen('http://127.0.0.1:"+str(port)+"/healthz',timeout=2)"
   if run(['docker','exec',name,'python','-c',code],False).returncode==0:break
   time.sleep(1)
  else:raise RuntimeError('恢复候选健康验证失败')
 finally:
  assert json.loads(run(['docker','inspect','--format','{{json .Config.Labels}}',name]).stdout)['familyapp.recovery-check']==name
  run(['docker','rm','-f','-v',name])
 report['services'][service]={'tag':tag,'image_id':run(['docker','image','inspect','--format','{{.Id}}',tag]).stdout.strip(),
 'base_image_id':base,'dependency_versions_match':True,'restored_dependency_packages':sorted(changed),'dependency_versions':original,'program_files_sha256':files,'health':'passed'}
 print(service+'恢复候选健康通过',flush=True)
context=OUT/'nginx';context.mkdir()
data=subprocess.run(['docker','cp','familyapp-nginx:/etc/nginx/conf.d/default.conf','-'],capture_output=True)
assert data.returncode==0
with tarfile.open(fileobj=io.BytesIO(data.stdout)) as t:
 members=[m for m in t.getmembers() if m.isfile()];assert len(members)==1
 config=t.extractfile(members[0]).read()
(context/'default.conf').write_bytes(config)
base='nginx:1.27-alpine'
assert '1.27.5' in run(['docker','run','--rm','--network','none',base,'nginx','-v']).stderr
(context/'Dockerfile').write_text('FROM nginx:1.27-alpine'+chr(10)+'COPY default.conf /etc/nginx/conf.d/default.conf'+chr(10))
tag='familyapp-recovery/nginx:20261014'
run(['docker','build','--network=none','--pull=false','-t',tag,str(context)])
args=['docker','run','--rm','--network','none']
for name in ('backend','frontend','wechat-service','agent-service','tool-service','tp-task-service','tp-agent-service'):
 args+=['--add-host',name+':127.0.0.1']
run(args+[tag,'nginx','-t'])
report['services']['nginx']={'tag':tag,'image_id':run(['docker','image','inspect','--format','{{.Id}}',tag]).stdout.strip(),
 'runtime_config_sha256':hashlib.sha256(config).hexdigest(),'nginx_version':'1.27.5','config_test':'passed'}
(ROOT/'docs/daily-runtime-recovery.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+chr(10))
print('三个旧服务恢复候选已准备，程序与配置摘要记录完成')

