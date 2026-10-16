// frontend/src/api/decomposeSession.js（D-1 真/D-2 Mock/D-4 Mock/D-5/D-6/D-7 真）
import client from './client.js';

const BASE = '/agent/v1/decompose/sessions';

// ===== D-1 真：创建会话 =====
export async function createSession(sessionType, initPayload = {}) {
  const body = { session_type: sessionType, init_payload: initPayload };
  const data = await client.post(BASE, body);
  return data; // → { session_id: number }
}

export function getSession(id) { return client.get(`${BASE}/${id}`); }
export function updateProposal(id, tasks) { return client.put(`${BASE}/${id}/proposal`, tasks); }

// ===== D-5/D-6/D-7 真：终态写业务表 =====
export async function confirm(sessionId) {
  return client.post(`${BASE}/${sessionId}/confirm`);
}
export async function saveProfileOnly(sessionId) {
  return client.post(`${BASE}/${sessionId}/save-profile-only`);
}
export async function cancel(sessionId) {
  return client.delete(`${BASE}/${sessionId}`);
}

// ===== D-2 Mock：chatStreamMock（事件格式与真实 A-3 §3.3 完全一致；前端 0 改动接真）=====
export function chatStreamMock(sessionId, userMsg /* string */, { onEvent, onError, onClose, opts = {} } = {}) {
  // opts = { demoRoundIndex?:number, initProfileSnapshot?:any }
  // 返回 controller{cancel()} — 供 Abort
  const controller = { _cancelled: false, cancel(){ this._cancelled = true; } };
  const timers = [];
  (async () => {
    try {
      // ① typing 600ms
      fire('typing', { display_name: 'AI助手', delay_ms: 600 });
      await sleep(600);
      if (controller._cancelled) return;

      // ② 根据关键词路由 reply（3 轮一键演示剧本：见 §3.3.1；否则默认问答）
      let reply = '我明白了！我们继续补细节吧～';
      let suggestOptions = ['好的','直接拆解','跳过问答'];
      let roundIndex = opts.demoRoundIndex ?? 0;
      let phase = 'asking';
      let patchPreview = null;

      if (opts.demoRoundIndex === 0) {
        reply = '收到～先问第一个问题：宝宝目前喝什么奶粉？有过敏食物吗？';
        suggestOptions = ['飞鹤星飞帆','金领冠','过敏：牛奶','过敏：鸡蛋'];
        roundIndex = 1;
      } else if (opts.demoRoundIndex === 1 && /飞鹤|奶粉/.test(userMsg)) {
        reply = '好的，奶粉品牌记下来啦！第二个问题：每天的作息是几点起、午睡多久、晚上几点睡呀？';
        suggestOptions = ['7点起, 12点午睡2h, 21点睡','作息不规律','想调整作息'];
        roundIndex = 2;
      } else if (opts.demoRoundIndex === 2 && /7点|午睡|作息/.test(userMsg)) {
        reply = '太棒啦！作息也收集到了～我已经把档案更新的预览给您准备好啦👇';
        suggestOptions = ['好的，继续拆解','我还想补细节'];
        phase = 'extracted_patch';
        roundIndex = 3;
        // 构造 patch_preview 事件
        const babyId = opts.initProfileSnapshot?.babyId ?? '1';
        patchPreview = { [String(babyId)]: {
          patch_obj: {
            milk_brand: '飞鹤星飞帆',
            wake_up_time: '07:00',
            nap_start: '12:00',
            nap_end: '14:00',
            bed_time: '21:00'
          },
          removed_keys: []
        }};
      } else if (/鸡蛋|过敏/.test(userMsg)) {
        reply = '收到过敏信息，已加入档案预览～';
        phase = 'extracted_patch';
        roundIndex = roundIndex + 1;
        const id = opts.initProfileSnapshot?.babyId ?? '1';
        patchPreview = { [String(id)]: { patch_obj: {}, removed_keys: ['allergy_list?no, 改成新增鸡蛋'] } };
      }

      fire('message', { phase, reply_content: reply, suggest_options: suggestOptions, round_index: roundIndex });
      await sleep(300);
      if (controller._cancelled) return;

      if (patchPreview) {
        fire('patch_preview', { profile_patch_preview: patchPreview });
        await sleep(200);
        if (controller._cancelled) return;
      }

      fire('done', { final_phase: phase });
      if (onClose) onClose();
    } catch (e) {
      if (controller._cancelled) return;
      fire('error', { msg: e.message || 'Mock 聊天出错啦' });
      if (onError) onError(e);
    }
  })();

  function fire(type, dataObj) {
    if (controller._cancelled) return;
    const data = JSON.stringify(dataObj);
    if (onEvent) onEvent(type, data);
  }
  function sleep(ms){ return new Promise(r => { const id = setTimeout(()=>r(), ms); timers.push(id); controller._timers = timers; }); }

  return { cancel: () => { controller._cancelled = true; if(timers) timers.forEach(clearTimeout); } };
}

// ===== D-4 Mock：decomposeStreamMock（事件格式与真实 A-4 §3.4 完全一致）=====
export function decomposeStreamMock(sessionId, { onEvent, onError, onClose } = {}) {
  const controller = { _cancelled:false, cancel(){ this._cancelled = true; } };
  const timers = [];
  (async () => {
    try {
      fire('typing', { display_name:'AI助手', delay_ms:800 });
      await sleep(800); if(controller._cancelled) return;

      fire('reasoning', { reasoning: '🤔 我先梳理了档案：6个月宝宝 + 飞鹤 + 作息 7-14-21 → 基于这些，拆解 3 条今天的任务：\n1) 早上冲奶（高优先级）\n2) 午餐辅食注意鸡蛋过敏\n3) 洗澡+睡前仪式（与作息匹配）' });
      await sleep(1200); if(controller._cancelled) return;

      const today = new Date(); today.setDate(today.getDate()+1);
      const iso = today.toISOString().slice(0,10);
      const tasks = [
        { title:'早上7点给宝宝冲奶 150ml 飞鹤星飞帆', description:'水温 45°C，150ml 水 + 5 勺奶粉', due_date: `${iso}T07:00:00`, priority:'HIGH', category_id:null, assignee_profile_id:null },
        { title:'13:30 午餐辅食：燕麦米粉（避免鸡蛋过敏）', description:'燕麦米粉 2 勺；不含鸡蛋/牛奶成分的婴儿果泥', due_date:`${iso}T13:30:00`, priority:'MEDIUM', category_id:null, assignee_profile_id:null },
        { title:'20:15 洗澡 + 21:00 睡前仪式（关灯讲故事）', description:'洗澡水温 38°C；睡前不看电子屏', due_date:`${iso}T20:15:00`, priority:'MEDIUM', category_id:null, assignee_profile_id:null }
      ];
      fire('proposal', { proposed_tasks: tasks });
      await sleep(300); if(controller._cancelled) return;

      fire('done', { count: 3, patch_merged: true });
      if (onClose) onClose();
    } catch (e) {
      if(controller._cancelled) return;
      fire('error', { msg: e.message || 'Mock 拆解出错啦' });
      if (onError) onError(e);
    }
  })();
  function fire(type, dataObj){ if(controller._cancelled) return; if(onEvent) onEvent(type, JSON.stringify(dataObj)); }
  function sleep(ms){ return new Promise(r => { const id = setTimeout(r, ms); timers.push(id); controller._timers = timers; }); }
  return { cancel: () => { controller._cancelled = true; if(timers) timers.forEach(clearTimeout); } };
}

export default { createSession, chatStreamMock, decomposeStreamMock, confirm, saveProfileOnly, cancel };
