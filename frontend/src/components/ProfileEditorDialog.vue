<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="min(820px, 94vw)"
    top="5vh"
    :close-on-click-modal="false"
    :close-on-press-escape="!isDirty"
    destroy-on-close
    @close="$emit('update:modelValue', false)"
  >
    <!-- 顶部 Steps 进度条 -->
    <el-steps :active="step + 1" finish-status="success" simple v-if="mode==='create'" class="top-steps">
      <el-step title="① 选档案类型" description="点击下方卡片" />
      <el-step title="② 填详细信息" description="老人/大字号一目了然" />
    </el-steps>

    <!-- ================ Step 1: 选类型 5 张卡 ================ -->
    <div v-if="mode==='create' && step===0" class="type-grid">
      <div
        v-for="c in typeCards"
        :key="c.type"
        class="type-card"
        :class="{active: selectedType === c.type}"
        @click="selectedType = c.type"
        role="button"
        :aria-label="`选${c.text}类型`"
      >
        <div class="tc-emoji">{{ c.emoji }}</div>
        <div class="tc-text">{{ c.text }}</div>
        <div class="tc-hint">{{ c.hint }}</div>
      </div>
    </div>

    <!-- ================ Step 2: 填字段 ================ -->
    <div v-if="step===1" class="step2-wrap">
      <!-- 顶部：类型胶囊 + 公共字段 (姓名/关系/性别/标签) -->
      <div class="section-card">
        <div class="sec-head"><TypeBadge :type="selectedType" /> <span class="sec-title">基础信息</span></div>
        <el-form label-position="top" size="large" class="form-grid-2">
          <el-form-item label="姓名 *（必填）">
            <el-input v-model="form.name" maxlength="20" show-word-limit class="submit-input" placeholder="请输入姓名或称呼" />
          </el-form-item>
          <el-form-item label="关系 *（必填，如：爸爸/奶奶/自己）">
            <el-input v-model="form.relation" maxlength="10" show-word-limit class="submit-input" placeholder="在家庭中的角色称呼" />
          </el-form-item>
          <el-form-item label="性别">
            <el-radio-group v-model="form.gender" size="large">
              <el-radio value="MALE">👨 男</el-radio>
              <el-radio value="FEMALE">👩 女</el-radio>
              <el-radio value="PRIVATE">🙈 保密</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="自定义头像 emoji（留空自动）">
            <el-input v-model="form.avatarEmoji" maxlength="4" class="submit-input" placeholder="例如 👶 或 不填" />
          </el-form-item>
          <el-form-item label="标签（自定义，多个按回车）" class="span-2">
            <el-select
              v-model="form.tags"
              multiple
              filterable
              allow-create
              default-first-option
              size="large"
              placeholder="选择或输入标签，如：过敏体质/高血压/住校"
              class="full-width"
            >
              <el-option v-for="t in defaultTags[selectedType] || []" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <!-- 动态字段（白名单） -->
      <div class="section-card" v-if="fieldCfg.length">
        <div class="sec-head"><span class="sec-title">{{ typeText }}档案专属字段</span><span class="sec-sub">（按白名单校验，仅保存允许的字段）</span></div>
        <el-form label-position="top" size="large" class="form-grid-2">
          <template v-for="fc in fieldCfg" :key="fc.key">
            <!-- el-input / el-input-number / el-select-multiple / el-time-picker / el-date-picker -->
            <el-form-item :label="fc.labelZh">
              <!-- el-input -->
              <el-input
                v-if="fc.component === 'el-input'"
                v-model="form.profileData[fc.key]"
                class="submit-input"
                :placeholder="fc.config.placeholder"
              />
              <!-- el-input-number -->
              <el-input-number
                v-else-if="fc.component === 'el-input-number'"
                v-model="form.profileData[fc.key]"
                size="large"
                :min="fc.config.min"
                :max="fc.config.max"
                :step="fc.config.step"
                controls-position="right"
                class="full-width"
                :placeholder="fc.config.placeholder"
              />
              <!-- el-select-multiple（语义映射：Element Plus 实际是 <el-select multiple filterable allow-create>） -->
              <el-select
                v-else-if="fc.component === 'el-select-multiple'"
                v-model="form.profileData[fc.key]"
                multiple
                filterable
                allow-create
                default-first-option
                size="large"
                :placeholder="fc.config.placeholder"
                class="full-width"
              >
                <el-option v-for="s in (fc.config.suggestions || [])" :key="s" :label="s" :value="s" />
              </el-select>
              <!-- el-time-picker -->
              <el-time-picker
                v-else-if="fc.component === 'el-time-picker'"
                v-model="form.profileData[fc.key]"
                size="large"
                :format="fc.config.format"
                value-format="HH:mm"
                :placeholder="fc.config.placeholder"
                class="full-width"
              />
              <!-- el-date-picker -->
              <el-date-picker
                v-else-if="fc.component === 'el-date-picker'"
                v-model="form.profileData[fc.key]"
                size="large"
                :type="fc.config.type || 'date'"
                value-format="YYYY-MM-DD"
                :placeholder="fc.config.placeholder"
                class="full-width"
              />
            </el-form-item>
          </template>
        </el-form>
      </div>
    </div>

    <!-- ================ 底部按钮行 ================ -->
    <template #footer>
      <div class="dialog-footer">
        <el-button size="large" @click="onCancelClick">
          {{ step===1 ? '再改改' : '关闭' }}
        </el-button>
        <el-button
          v-if="mode==='create' && step===0"
          size="large"
          type="primary"
          :disabled="!selectedType"
          @click="step = 1"
        >
          下一步 ➡️
        </el-button>
        <el-button
          v-if="mode==='create' && step===1"
          size="large"
          @click="step = 0"
        >
          ⬅️ 上一步
        </el-button>
        <el-button
          v-if="step===1"
          size="large"
          type="success"
          :disabled="!canSave"
          @click="onSubmit"
        >
          💾 {{ mode==='create' ? '新建档案' : '保存修改' }}
        </el-button>
        <el-tooltip v-if="!canSave && store.role==='CHILD'" content="小孩角色无法新建/编辑档案，请联系家庭管理员" placement="top">
          <span class="save-hint">🔒 权限受限</span>
        </el-tooltip>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import TypeBadge from './TypeBadge.vue';
import { fieldRenderConfig, profileTypeName, whitelistKeys } from '../utils/profileRender.js';
import { useProfilesStore } from '../stores/profiles.js';

const store = useProfilesStore();

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  profile: { type: Object, default: null }   // 编辑模式传入
});
const emit = defineEmits(['update:modelValue', 'saved']);

/* ===== 基础状态 ===== */
const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
});
const mode = computed(() => props.profile ? 'edit' : 'create');
const dialogTitle = computed(() => mode.value === 'create' ? '➕ 新建家庭档案' : '✏️ 编辑家庭档案');

const step = ref(0);              // create: 0→选类型，1→填字段；edit: 默认强制1
const selectedType = ref(props.profile?.profileType || null);
const isDirty = ref(false);

/* ===== 类型卡片 ===== */
const typeCards = [
  { type:'BABY',  emoji:'👶', text:'宝宝',   hint:'0~3 岁婴幼儿' },
  { type:'ELDER', emoji:'👴', text:'老人',   hint:'50 岁以上长辈' },
  { type:'ADULT', emoji:'👨', text:'成人',   hint:'18~50 岁家人' },
  { type:'PET',   emoji:'🐾', text:'宠物',   hint:'猫狗/其他宠物' },
  { type:'OTHER', emoji:'👤', text:'其他',   hint:'自定义角色' }
];
const defaultTags = {
  BABY:  ['过敏体质','早产儿','母乳喂养','配方奶','辅食添加中'],
  ELDER: ['高血压','糖尿病','独居','需要协助服药','定期复查'],
  ADULT: ['上班族','远程办公','出差多','夜班','备孕中'],
  PET:   ['室内养','已绝育','已疫苗','定期驱虫','处方粮'],
  OTHER: ['纪念日','重要联系人','家庭医生','老师','保姆']
};
const typeText = computed(() => profileTypeName(selectedType.value));
const fieldCfg = computed(() => fieldRenderConfig(selectedType.value));

/* ===== 表单 ===== */
const defaultForm = () => ({
  name: '', relation: '', gender: 'PRIVATE', avatarEmoji: '', tags: [],
  profileData: {}
});
const form = reactive(defaultForm());

function resetForm() {
  Object.assign(form, defaultForm());
  isDirty.value = false;
  step.value = 0;
  if (props.profile) {
    step.value = 1;
    selectedType.value = props.profile.profileType;
    form.name = props.profile.name || '';
    form.relation = props.profile.relation || '';
    form.gender = props.profile.gender || 'PRIVATE';
    form.avatarEmoji = props.profile.avatarEmoji || '';
    form.tags = Array.isArray(props.profile.tags) ? [...props.profile.tags] : [];
    form.profileData = Object.assign({}, props.profile.profileData || {});
  }
}

// watch 任一输入标记 dirty（二次确认防丢）
watch(form, () => { isDirty.value = true; }, { deep: true });
watch(selectedType, () => { isDirty.value = true; });

// Dialog 打开 / profile 切换 → 重置
watch([visible, ()=>props.profile?.id], ([v]) => {
  if (v) resetForm();
});
// Edit 模式直接 Step 2
watch(() => props.profile, (np) => {
  if (np && visible.value) { step.value = 1; selectedType.value = np.profileType; resetForm(); }
}, { immediate: true });

/* ===== 权限 & 保存校验 ===== */
const canSave = computed(() => {
  if (store.role === 'CHILD') return false;
  if (!selectedType.value) return false;
  if (!form.name?.trim() || !form.relation?.trim()) return false;
  return true;
});

function stripToWhitelist() {
  const allowed = new Set(whitelistKeys(selectedType.value));
  const clean = {};
  const removed = [];
  Object.entries(form.profileData || {}).forEach(([k, v]) => {
    if (allowed.has(k)) clean[k] = v; else removed.push(k);
  });
  if (removed.length) {
    ElMessage.warning(`已自动过滤 ${removed.length} 个非白名单字段：${removed.join('、')}（不影响合法字段）`);
  }
  return clean;
}

async function onSubmit() {
  if (!canSave.value) return;
  const cleanProfileData = stripToWhitelist();
  const payload = {
    profileType: selectedType.value,
    name: form.name.trim(),
    relation: form.relation.trim(),
    gender: form.gender,
    avatarEmoji: form.avatarEmoji || undefined,
    tags: form.tags,
    profileData: cleanProfileData
  };
  try {
    let result;
    if (mode.value === 'create') {
      result = await store.create(payload);
      ElMessage.success(`✅ 已新建「${result.name || form.name}」档案`);
    } else {
      result = await store.update(props.profile.id, payload);
      ElMessage.success(`✅ 已保存「${result.name || form.name}」修改`);
    }
    emit('saved', { mode: mode.value, profile: result });
    visible.value = false;
  } catch (e) {
    // client.js 已统一 ElMessage.error 处理
    console.error('[ProfileEditorDialog] save failed:', e);
  }
}

async function onCancelClick() {
  if (isDirty.value && step.value === 1) {
    try {
      await ElMessageBox.confirm(
        '当前有未保存的修改，确定要离开吗？',
        '离开确认',
        {
          confirmButtonText: '放弃离开',
          cancelButtonText: '继续编辑',
          distinguishCancelAndClose: true,
          cancelButtonClass: 'el-button--primary',
          type: 'warning'
        }
      );
      // confirm = 放弃
      visible.value = false;
    } catch (_) { /* cancel/close = 继续编辑 */ }
  } else {
    visible.value = false;
  }
}
</script>

<style scoped>
.top-steps { margin-bottom: 18px; }

/* ============ 类型卡 Step 0 ============ */
.type-grid {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 14px; padding: 8px 4px;
}
.type-card {
  border: 2px solid #e5e7eb; border-radius: 16px;
  padding: 20px 10px 18px; text-align: center;
  cursor: pointer; transition: all .18s ease;
  background: #fff; user-select: none;
}
.type-card:hover { transform: translateY(-2px); border-color: #bfdbfe; box-shadow: 0 6px 18px rgba(37,99,235,.08); }
.type-card.active { border-color: #2563EB; background: linear-gradient(180deg,#eff6ff,#fff); box-shadow: 0 8px 22px rgba(37,99,235,.15); }
.tc-emoji { font-size: 52px; line-height: 1; margin-bottom: 10px; }
.tc-text { font-size: 20px; font-weight: 700; color: #111827; margin-bottom: 4px; }
.tc-hint { font-size: 13px; color: #6b7280; }

/* ============ Step 2 布局 ============ */
.section-card {
  border: 1px solid #f1f5f9;
  border-radius: 12px;
  padding: 16px 18px 8px;
  margin-bottom: 16px;
  background: #fafafa;
}
.sec-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.sec-title { font-size: 18px; font-weight: 700; color: #1f2937; }
.sec-sub { font-size: 13px; color: #6b7280; margin-left: auto; }

.form-grid-2 {
  display: grid; grid-template-columns: 1fr 1fr; gap: 4px 18px;
}
.form-grid-2 :deep(.el-form-item) { margin-bottom: 14px; }
.span-2 { grid-column: 1 / -1; }
.full-width { width: 100%; }
.submit-input :deep(.el-input__wrapper) { min-height: 44px; }

/* ============ 底部按钮行 ============ */
.dialog-footer { display: flex; align-items: center; gap: 10px; justify-content: flex-end; }
.save-hint { font-size: 14px; color: #dc2626; margin-right: auto; }
</style>
