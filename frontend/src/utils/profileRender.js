/** 纯函数工具：profile 白名单 / 动态字段 / 排序 — 不依赖 Pinia/Vue */
const TYPE_ORDER = { BABY: 0, ELDER: 1, ADULT: 2, PET: 3, OTHER: 4 };
export function typePrioritySort(profiles) {
  if (!Array.isArray(profiles)) return [];
  return profiles.slice().sort((a, b) => {
    const oa = TYPE_ORDER[a.profileType] ?? 999;
    const ob = TYPE_ORDER[b.profileType] ?? 999;
    if (oa !== ob) return oa - ob;
    return new Date(a.createdAt || 0).getTime() - new Date(b.createdAt || 0).getTime();
  });
}
export function profileTypeName(t) {
  return ({ BABY:'宝宝', ELDER:'老人', ADULT:'成人', PET:'宠物', OTHER:'其他' })[t] || '其他';
}
export function avatarEmojiDefault(t) {
  return ({ BABY:'👶', ELDER:'👴', ADULT:'👨', PET:'🐾', OTHER:'👤' })[t] || '👤';
}
// §1.2 白名单 key（后端 ProfileDataSchema.java 1:1 双保险）
const BABY_KEYS = ['month_age','weight_kg','height_cm','allergy_list','chronic_diseases','diet_preferences','milk_brand','wake_up_time','bed_time','nap_start','nap_end','vaccines_done','vaccines_due'];
const ELDER_KEYS = ['birth_date','age','blood_type','allergy_list','chronic_diseases','common_meds','diet_preferences','exercise_habits','checkup_due_date','blood_pressure_target_systolic','blood_pressure_target_diastolic'];
const ADULT_KEYS = ['birth_date','age','blood_type','allergy_list','chronic_diseases','common_meds','diet_preferences','exercise_habits','checkup_due_date'];
const PET_KEYS = ['breed','birth_date','age_years','weight_kg','allergy_list','chronic_diseases','diet_preferences','last_vet_date','next_vet_date'];
const OTHER_KEYS = ['birth_date','notes','tags_custom'];
export function whitelistKeys(type) {
  return ({ BABY:BABY_KEYS, ELDER:ELDER_KEYS, ADULT:ADULT_KEYS, PET:PET_KEYS, OTHER:OTHER_KEYS })[type] || [];
}
// 动态字段渲染配置 → ProfileEditorDialog Step 2 用
const mkNum = (key,label,min,max,step=1) => ({ key, labelZh:label, component:'el-input-number', config:{min,max,step,placeholder:`请输入${label}`}});
const mkStrArr = (key,label,suggests=[]) => ({ key, labelZh:label, component:'el-select-multiple', config:{ suggestions: suggests, allowCreate:true, placeholder:`选择或输入${label}，多个用逗号分隔`}});
const mkTime = (key,label) => ({ key, labelZh:label, component:'el-time-picker', config:{ format:'HH:mm', placeholder:`选择${label}`}});
const mkDate = (key,label) => ({ key, labelZh:label, component:'el-date-picker', config:{ type:'date', placeholder:`选择${label}`}});
const mkStr = (key,label) => ({ key, labelZh:label, component:'el-input', config:{ placeholder:`请输入${label}`}});
export function fieldRenderConfig(type) {
  switch(type) {
    case 'BABY': return [
      mkNum('month_age','月龄（0-36）',0,36),
      mkNum('weight_kg','体重 kg（0-50，允许.1）',0,50,0.1),
      mkNum('height_cm','身高 cm（0-150）',0,150,0.1),
      mkStrArr('allergy_list','过敏食物',['牛奶','鸡蛋','花生','海鲜','小麦','芒果','大豆']),
      mkStrArr('chronic_diseases','慢性疾病',['先天性心脏病','先天性甲状腺功能减退']),
      mkStrArr('diet_preferences','饮食偏好',['母乳喂养','配方奶','辅食添加中','纯素','低盐']),
      mkStr('milk_brand','奶粉品牌'),
      mkTime('wake_up_time','起床时间'),
      mkTime('bed_time','入睡时间'),
      mkTime('nap_start','午休开始'),
      mkTime('nap_end','午休结束'),
      mkStrArr('vaccines_done','已完成疫苗',['乙肝第1剂','卡介苗','脊灰第1剂','百白破第1剂','麻腮风第1剂']),
      mkStrArr('vaccines_due','待接种疫苗',['乙肝第2剂','脊灰第2剂','百白破第2剂'])
    ];
    case 'ELDER': return [
      mkDate('birth_date','出生日期'),
      mkNum('age','年龄',50,120),
      mkStr('blood_type','血型（如 A+/O-/AB+）'),
      mkStrArr('allergy_list','过敏食物/药物',['青霉素','磺胺','海鲜','花生','牛奶']),
      mkStrArr('chronic_diseases','慢性疾病',['高血压','糖尿病','心脏病','关节炎','骨质疏松','慢性胃炎','高血脂']),
      mkStrArr('common_meds','常用药物',['降压药','二甲双胍','他汀','钙片','维生素D','阿司匹林']),
      mkStrArr('diet_preferences','饮食偏好',['低盐','低糖','低脂','素食','软食','流质']),
      mkStrArr('exercise_habits','运动习惯',['散步','太极','广场舞','游泳','骑车','慢跑','瑜伽']),
      mkDate('checkup_due_date','下次体检日期'),
      mkNum('blood_pressure_target_systolic','血压目标-高压（mmHg）',80,200),
      mkNum('blood_pressure_target_diastolic','血压目标-低压（mmHg）',40,140)
    ];
    case 'ADULT': return [
      mkDate('birth_date','出生日期'),
      mkNum('age','年龄',18,120),
      mkStr('blood_type','血型'),
      mkStrArr('allergy_list','过敏食物/药物',['青霉素','磺胺','海鲜','花生','花粉']),
      mkStrArr('chronic_diseases','慢性疾病',['高血压','糖尿病','痛风','高血脂','慢性胃炎','甲状腺结节']),
      mkStrArr('common_meds','常用药物/补剂',['维生素C','钙片','维生素D','鱼油','褪黑素']),
      mkStrArr('diet_preferences','饮食偏好',['低盐','低糖','低脂','素食','高蛋白','低碳水']),
      mkStrArr('exercise_habits','运动习惯',['跑步','骑车','游泳','健身','打球','徒步','瑜伽','太极']),
      mkDate('checkup_due_date','下次体检日期')
    ];
    case 'PET': return [
      mkStr('breed','品种（如金毛/英短/布偶）'),
      mkDate('birth_date','出生日期（可填领养日估计）'),
      mkNum('age_years','年龄（岁）',0,30,0.1),
      mkNum('weight_kg','体重 kg',0,100,0.1),
      mkStrArr('allergy_list','过敏食物/药物',['牛肉','鸡肉','海鲜','青霉素','磺胺']),
      mkStrArr('chronic_diseases','慢性疾病',['犬细小','猫瘟','肾衰竭','糖尿病','心脏病','皮肤病','关节发育不良']),
      mkStrArr('diet_preferences','饮食偏好/处方粮',['处方粮肠道','处方粮肾脏','无谷粮','生食','自制鲜食']),
      mkDate('last_vet_date','上次体检日期'),
      mkDate('next_vet_date','下次体检/疫苗日期')
    ];
    default: return [
      mkDate('birth_date','出生日期/纪念日期'),
      mkStr('notes','备注（可自由填写）'),
      mkStrArr('tags_custom','自定义标签')
    ];
  }
}
export default { typePrioritySort, profileTypeName, avatarEmojiDefault, whitelistKeys, fieldRenderConfig };
