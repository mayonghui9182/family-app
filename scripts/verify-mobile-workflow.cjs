// 仅用于本机 18081 内存后端和 15173 前端；创建独立测试账号，不访问线上服务。
// 通过 NODE_PATH 指向临时安装的 Playwright，截图保存在 /tmp/family-visual。
const { chromium, request } = require('playwright');
const fs = require('fs');
(async () => {
 const api = await request.newContext({baseURL:'http://127.0.0.1:18081/api/'});
 let token, fid;
 async function call(path, body, method = body ? 'POST' : 'GET') {
  const response = await api.fetch(path,{method,data:body,headers:{...(token?{'X-Access-Token':token}:{}),...(fid?{'X-Family-Id':String(fid)}:{})}});
  const json = await response.json(); if(json.code!==0) throw new Error(path+': '+JSON.stringify(json)); return json.data;
 }
 let account;
 for(let i=0;i<3;i++) {try {account=await call('auth/register',{email:`visual-${Date.now()}-${i}@example.test`,nickname:'验收用户',password:'Verify-local-123',familyName:'验收家庭'});break;}catch(e){if(i===2)throw e;}}
 token=account.accessToken; fid=account.familyId;
 const me=await call('auth/me');
 const second=await call('families',{name:'第二个家'});
 const families=await call('families');
 const family=families.find(f=>f.id===fid);
 const profiles=await call('v1/person-profiles');
 await call('tasks',{title:'验收买菜',assigneeProfileId:profiles[0].id,dueDate:'2026-01-01'});
 const draft=await call('agent/v1/decompose/sessions',{session_type:'TASK_CREATE',init_payload:{title:'明天准备晚饭'}});
 await call(`agent/v1/decompose/sessions/${draft.session_id}/proposal`,[{title:'买番茄'},{title:'洗蔬菜'}],'PUT');
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:Number(process.env.MOBILE_WIDTH || 375),height:812},deviceScaleFactor:1,isMobile:true,hasTouch:true});
 await context.addInitScript(({token,me,family,families})=>{localStorage.setItem('accessToken',token);localStorage.setItem('userInfo',JSON.stringify(me));localStorage.setItem('families',JSON.stringify(families));localStorage.setItem('currentFamily',JSON.stringify(family));localStorage.setItem('currentFamilyId',String(family.id));},{token,me,family,families});
 const page=await context.newPage(); const errors=[];page.on('pageerror',e=>errors.push(e.message));
 fs.mkdirSync('/tmp/family-visual',{recursive:true});
 for(const [name,path] of [['dashboard','/dashboard'],['tasks','/tasks'],['create','/tasks/new'],['draft',`/agent/draft/${draft.session_id}`]]) {
  await page.goto('http://127.0.0.1:15173'+path);await page.waitForTimeout(1200);
  if(name==='create') {
   await page.getByPlaceholder('任务标题，必填（最多 200 字）').fill('浏览器指派任务');
   await page.locator('.el-form-item').filter({hasText:'指派给家庭成员'}).locator('.el-select__wrapper').click();
   await page.getByRole('option').filter({hasText:'验收用户'}).click();
   await page.waitForTimeout(400);
   const selected=await page.locator('.el-form-item').filter({hasText:'指派给家庭成员'}).innerText();
   if(!selected.includes('验收用户')) throw new Error('指派未显示姓名');
  }
  await page.screenshot({path:`/tmp/family-visual/${name}.png`,fullPage:true});
  if(name==='create') {
   await page.getByRole('button',{name:'➕ 创建任务',exact:true}).click();
   await page.waitForURL('**/tasks');
   await page.getByText('浏览器指派任务',{exact:true}).waitFor();
   const mine=await call(`tasks/page?assigneeId=${me.userId}`);
   if(!mine.items.some(t=>t.title==='浏览器指派任务'))throw new Error('本人列表缺少新任务');
  }
  const widths=await page.evaluate(()=>({body:document.documentElement.scrollWidth,viewport:innerWidth}));
  if(widths.body>widths.viewport)throw new Error(name+' 横向溢出');
  console.log(name,JSON.stringify(widths),await page.locator('h1,h2').allTextContents());
 }
 await page.locator('#draft-title-0').fill('买新鲜番茄');
 await page.getByRole('button',{name:'确认添加 2 件事'}).click();
 await page.getByText('这些待办已经创建，请勿重复添加。').waitFor();
 if(await page.getByRole('link',{name:'查看这件事'}).count()!==2)throw new Error('缺少结果链接');
 await page.screenshot({path:'/tmp/family-visual/confirmed.png',fullPage:true});
 await page.goto('http://127.0.0.1:15173/tasks');await page.waitForTimeout(600);
 await page.locator('.header-center .el-select__wrapper').click();
 await page.getByRole('option',{name:'第二个家',exact:true}).click();await page.waitForTimeout(1000);
 if(await page.getByText('验收买菜',{exact:true}).count())throw new Error('切换后仍有旧家庭任务');
 await page.screenshot({path:'/tmp/family-visual/switched.png',fullPage:true});
 const anonymous=await browser.newContext({viewport:{width:Number(process.env.MOBILE_WIDTH || 375),height:812}});
 const login=await anonymous.newPage();
 await login.goto(`http://127.0.0.1:15173/agent/draft/${draft.session_id}`);
 await login.getByPlaceholder('邮箱或手机号').fill(account.email);
 await login.getByPlaceholder('请输入密码',{exact:true}).fill('Verify-local-123');
 await login.getByRole('button',{name:'提交登录'}).click();
 await login.waitForURL(`**/agent/draft/${draft.session_id}`);
 await login.getByText('这些待办已经创建，请勿重复添加。').waitFor();
 await login.screenshot({path:'/tmp/family-visual/login-return.png',fullPage:true});
 await anonymous.close();
 console.log('密码登录后恢复草稿深链通过');
 console.log('页面错误',JSON.stringify(errors)); if(errors.length)throw new Error('存在浏览器错误');
 await browser.close();await api.dispose();console.log('移动端浏览器流程通过');
})().catch(e=>{console.error(e);process.exit(1)});
