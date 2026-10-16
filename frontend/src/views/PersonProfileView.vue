<template>
  <div class="person-profile-view">
    <!-- ============ 顶部栏 ============ -->
    <div class="top-bar">
      <div class="title-wrap">
        <span class="icon">👥</span>
        <h1 class="page-title">家庭人员档案</h1>
        <el-tag size="large" effect="plain" class="count-badge" v-if="store.profiles.length">
          共 {{ store.profiles.length }} 位成员
        </el-tag>
      </div>
      <div class="top-actions">
        <el-input
          v-model="searchText"
          size="large"
          clearable
          placeholder="🔍 搜索姓名/关系/标签"
          class="search-input submit-input"
          :prefix-icon="Search"
        />
        <el-button
          v-if="store.canEditProfile"
          size="large"
          type="primary"
          @click="openCreate"
        >
          ➕ 新建档案
        </el-button>
        <el-button size="large" @click="store.fetchList({force:true})">
          🔄 刷新
        </el-button>
      </div>
    </div>

    <!-- 加载 / 空状态 -->
    <el-empty
      v-if="store.loading"
      description="正在加载档案列表…"
      :image-size="120"
    />
    <div v-else-if="!filteredProfiles.length" class="empty-wrap">
      <el-empty description="还没有家庭成员档案" :image-size="160">
        <template #image>
          <div class="empty-emoji">👨‍👩‍👧‍👦</div>
        </template>
        <p class="empty-hint">点击右上角「➕ 新建档案」添加第一位家人吧～（宝宝/老人/成人/宠物都支持）</p>
        <el-button
          v-if="store.canEditProfile"
          size="large" type="primary" @click="openCreate"
          class="empty-cta"
        >
          ➕ 立即新建第一份档案
        </el-button>
      </el-empty>
    </div>

    <!-- ============ ProfileCard 网格（统一 A 排序）============ -->
    <div v-else class="profile-grid">
      <ProfileCard
        v-for="p in filteredProfiles"
        :key="p.id"
        :profile="p"
        @view="openDrawer(p, 'detail')"
        @ai-interview="openDrawer(p, 'ai')"
      />
    </div>

    <!-- ============ el-drawer 👁️详情 / 🤖AI访谈 双模式 ============ -->
    <el-drawer
      v-model="drawerOpen"
      :title="drawerProfile?.name || '档案详情'"
      direction="rtl"
      :size="'min(540px, 92vw)'"
      destroy-on-close
      :with-header="false"
    >
      <template v-if="drawerProfile">
        <!-- 自定义头：emoji + 切换 tab -->
        <div class="drawer-header">
          <div class="dh-left">
            <EmojiAvatar :size="64" :emoji="drawerProfile.avatarEmoji" :profile-type="drawerProfile.profileType" :name="drawerProfile.name" />
            <div class="dh-info">
              <div class="dh-name">{{ drawerProfile.name }}</div>
              <div class="dh-sub">
                <TypeBadge :type="drawerProfile.profileType" size="default" />
                <span class="dh-relation">关系：{{ drawerProfile.relation || '未填' }}</span>
              </div>
            </div>
          </div>
          <div class="dh-tabs">
            <el-button
              size="large"
              :type="drawerMode==='detail'?'primary':'default'"
              @click="drawerMode='detail'"
            >👁️ 详情</el-button>
            <el-button
              size="large"
              :type="drawerMode==='ai'?'primary':'default'"
              data-ai="true"
              class="ai-entry-btn"
              @click="drawerMode='ai'"
            >🤖 AI访谈</el-button>
          </div>
        </div>

        <!-- 模式1: 👁️ 详情 -->
        <div v-if="drawerMode==='detail'" class="drawer-detail">
          <div class="sec-card">
            <div class="sec-title">📇 基础信息</div>
            <dl class="kv-list">
              <div class="kv"><dt>关系</dt><dd>{{ drawerProfile.relation || '未填' }}</dd></div>
              <div class="kv"><dt>性别</dt><dd>{{ genderText(drawerProfile.gender) }}</dd></div>
              <div class="kv" v-if="drawerProfile.tags && drawerProfile.tags.length">
                <dt>标签</dt>
                <dd>
                  <el-tag size="large" effect="plain" v-for="t in drawerProfile.tags.slice(0,10)" :key="t" class="tag-mg">{{ t }}</el-tag>
                </dd>
              </div>
              <div class="kv"><dt>创建时间</dt><dd>{{ fmt(drawerProfile.createdAt) }}</dd></div>
              <div class="kv"><dt>最近更新</dt><dd>{{ fmt(drawerProfile.updatedAt) }}</dd></div>
            </dl>
          </div>

          <div class="sec-card" v-if="detailFieldRows.length">
            <div class="sec-title">
              {{ profileTypeName(drawerProfile.profileType) }}专属档案
              <span class="sec-sub">（共 {{ detailFieldRows.length }} 项）</span>
            </div>
            <dl class="kv-list">
              <div class="kv" v-for="row in detailFieldRows" :key="row.key">
                <dt>{{ row.label }}</dt>
                <dd>
                  <template v-if="row.type==='array' && row.value && row.value.length">{{ row.value.join('、') || '—' }}</template>
                  <template v-else-if="row.value!=null && row.value!==''">{{ row.value }}</template>
                  <template v-else><span class="muted">未填写</span></template>
                </dd>
              </div>
            </dl>
          </div>

          <!-- 详情底部 操作按钮 行 -->
          <div class="drawer-actions">
            <el-button
              v-if="store.canDeleteProfile"
              size="large"
              type="danger"
              @click="onDeleteClick(drawerProfile)"
            >🗑️ 删除档案</el-button>
            <el-button
              v-if="store.canEditProfile"
              size="large"
              type="primary"
              @click="openEdit(drawerProfile)"
            >✏️ 编辑档案</el-button>
          </div>
        </div>

        <!-- 模式2: 🤖 AI访谈 → 跳转到独立 /chat 页面（S6 E2E B 流程入口） -->
        <div v-else class="drawer-ai-panel-placeholder">
          <el-card shadow="never" class="ph-card">
            <template #header>
              <div class="ph-head">
                <span class="ph-emoji">🤖</span>
                <span class="ph-title">AI 访谈模式</span>
              </div>
            </template>
            <div class="ph-body">
              <div class="ph-big-emoji">🧠</div>
              <h3 class="ph-h">AI 帮您一次补全「{{ drawerProfile.name }}」的档案</h3>
              <p class="ph-p">
                进入 AI 拆解页 → 和 AI 聊 3 轮（比如：宝宝几点睡？对什么过敏？）→ 预览档案补丁 → 确认后：<br>
                ① 立即更新当前成员档案（无需回到这里再编辑）<br>
                ② 如有适合的周期性待办，顺带生成任务清单
              </p>
              <div class="ph-actions">
                <el-button size="large" @click="drawerMode='detail'">
                  ⬅️ 先回到详情页逛逛
                </el-button>
                <el-button
                  size="large"
                  type="primary"
                  data-ai="true"
                  class="start-ai-interview-btn"
                  @click="handleAiInterview"
                >
                  🚀 开始 AI 访谈 → 进入 AI拆解页
                </el-button>
              </div>
            </div>
          </el-card>
        </div>
      </template>
    </el-drawer>

    <!-- ============ 新建 / 编辑 对话框 ============ -->
    <ProfileEditorDialog
      v-model="createDlgOpen"
      @saved="onCreated"
    />
    <ProfileEditorDialog
      v-if="editDlgProfileId"
      v-model="editDlgOpen"
      :profile="editDlgProfileId ? store.profiles.find(p=>p.id===editDlgProfileId) : null"
      @saved="onEdited"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import ProfileCard from '../components/ProfileCard.vue';
import ProfileEditorDialog from '../components/ProfileEditorDialog.vue';
import EmojiAvatar from '../components/EmojiAvatar.vue';
import TypeBadge from '../components/TypeBadge.vue';
import { useProfilesStore } from '../stores/profiles.js';
import { fieldRenderConfig, profileTypeName } from '../utils/profileRender.js';

const store = useProfilesStore();
const router = useRouter();

/* ===== 顶栏 ===== */
const searchText = ref('');
const createDlgOpen = ref(false);
const editDlgOpen = ref(false);
const editDlgProfileId = ref(null);

/* ===== Drawer ===== */
const drawerOpen = ref(false);
const drawerMode = ref('detail');   // 'detail' | 'ai'
const drawerProfileId = ref(null);
const drawerProfile = computed(() =>
  drawerProfileId.value ? store.profiles.find(p => p.id === drawerProfileId.value) : null
);

/* ===== 过滤 + 排序（统一 A 排序 getter）===== */
const filteredProfiles = computed(() => {
  const base = store.sortedProfiles;   // Constraint #5: 排序 A 统一 getter
  const q = searchText.value?.trim().toLowerCase();
  if (!q) return base;
  return base.filter(p => {
    const hay = [
      p.name, p.relation,
      ...(p.tags || []),
      p.profileData?.breed, p.profileData?.milk_brand
    ].filter(Boolean).join(' ').toLowerCase();
    return hay.includes(q);
  });
});

/* ===== 详情 KV 列表：按 fieldRenderConfig 顺序 + labelZh 展示 + 数组join ===== */
const detailFieldRows = computed(() => {
  if (!drawerProfile.value) return [];
  const pd = drawerProfile.value.profileData || {};
  const cfg = fieldRenderConfig(drawerProfile.value.profileType);
  return cfg.map(fc => ({
    key: fc.key,
    label: fc.labelZh,
    value: pd[fc.key],
    type: fc.component === 'el-select-multiple' ? 'array' : 'scalar'
  }));
});

/* ===== 生命周期 ===== */
onMounted(async () => {
  try {
    await store.fetchList();
  } catch (e) {
    // client.js 已统一 ElMessage.error
    console.warn('[PersonProfileView] fetchList failed, will retry on refresh:', e?.message);
  }
});

/* ===== 事件处理 ===== */
function openCreate() { createDlgOpen.value = true; }
function openEdit(profile) {
  editDlgProfileId.value = profile.id;
  editDlgOpen.value = true;
}
function openDrawer(profile, mode) {
  drawerProfileId.value = profile.id;
  drawerMode.value = mode;
  drawerOpen.value = true;
}
/**
 * S6 E2E B 流程前置：档案页 AI 访谈 deep-link → 独立 /chat 拆解页
 *   - PROFILE_INTERVIEW session_type（后端枚举已支持）
 *   - assigneeProfileId / title 都传，让 DecomposeChatPageWrapper.initForm 直接接管
 *   - 先关 drawer（避免跳转回来后还开着影响体验）
 */
function handleAiInterview() {
  const p = drawerProfile.value;
  if (!p?.id) {
    ElMessage.warning('请先选一位家庭成员再开始 AI 访谈～');
    return;
  }
  drawerOpen.value = false;
  const query = {
    assigneeProfileId: String(p.id),
    title: 'AI完善档案：' + (p.name || '家庭成员'),
    session_type: 'PROFILE_INTERVIEW'
  };
  try {
    router.push({ name: 'chat', query }).catch(() => {
      // name fallback：路由配置不一定有 name='chat'，退化成 path /chat
      router.push({ path: '/chat', query });
    });
  } catch (err) {
    // 极端兜底：任何 router 异常都不抛白屏
    ElMessage.error('跳转 AI 拆解页失败，请从侧边栏手动进入 AI 拆解');
    console.warn('[PersonProfileView] handleAiInterview router.push error:', err);
  }
}
function onCreated({ profile }) {
  // store.create 已 push；如果刚创建，立即打开详情抽屉让老人看到成果
  drawerProfileId.value = profile?.id;
  if (drawerProfileId.value) { drawerMode.value = 'detail'; drawerOpen.value = true; }
}
function onEdited() {
  // 编辑完成，刷新 drawerProfile computed 已 reactive（store.update 已 splice）
  ElMessage.success('档案修改已保存');
}

async function onDeleteClick(p) {
  // 二次确认（符合 Global Constraint #7：取消按钮默认焦点 + 中文安全词）
  try {
    await ElMessageBox.confirm(
      `确认删除「${p.name}」档案吗？关联到该成员的待办任务将保留，只是不再显示此人。`,
      '删除档案',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '先保留',
        distinguishCancelAndClose: true,
        cancelButtonClass: 'el-button--primary',
        type: 'warning'
      }
    );
    await store.remove(p.id);
    ElMessage.success(`✅ 已删除「${p.name}」档案`);
    drawerOpen.value = false;
  } catch (e) {
    if (e?.code === 40901 || String(e?.message || '').includes('有待办')) {
      ElMessage.warning('该档案有待办任务绑定，请先处理任务再删除。');
    }
    // 其余 cancel/close 不提示
  }
}

/* ===== 工具函数 ===== */
function genderText(g) {
  return ({ MALE:'👨 男', FEMALE:'👩 女', PRIVATE:'🙈 保密' })[g] || '—';
}
function fmt(iso) {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString('zh-CN', { hour12: false }); }
  catch { return iso; }
}
</script>

<style scoped>
.person-profile-view {
  padding: 18px 24px 40px;
  max-width: 1400px;
  margin: 0 auto;
}

/* ===== 顶部 ===== */
.top-bar {
  display: flex; align-items: center; flex-wrap: wrap;
  gap: 14px; margin-bottom: 22px;
  padding: 14px 18px;
  background: linear-gradient(135deg,#eff6ff,#f5f3ff);
  border-radius: 16px;
  border: 1px solid #e0e7ff;
}
.title-wrap { display: flex; align-items: center; gap: 12px; flex: 1 1 300px; }
.icon { font-size: 34px; line-height: 1; }
.page-title {
  font-size: 26px; font-weight: 800; color: #0f172a; margin: 0;
}
.count-badge { font-size: 15px; }
.top-actions { display: flex; align-items: center; gap: 10px; flex: 1 1 380px; justify-content: flex-end; }
.search-input { width: min(420px, 100%); }
.submit-input :deep(.el-input__wrapper) { min-height: 46px; }

/* ===== 空态 ===== */
.empty-wrap { margin: 60px auto; max-width: 480px; }
.empty-emoji { font-size: 96px; line-height: 1; margin-bottom: 10px; }
.empty-hint { color: #475569; font-size: 16px; margin: 0 0 20px; }
.empty-cta { min-height: 52px; font-size: 18px; padding: 0 30px; }

/* ===== 卡片网格 ===== */
.profile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

/* ===== Drawer ===== */
.drawer-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  padding: 16px 18px; border-bottom: 1px solid #eef2f7;
  margin-bottom: 14px;
  gap: 12px;
  flex-wrap: wrap;
}
.dh-left { display: flex; align-items: center; gap: 14px; }
.dh-name { font-size: 24px; font-weight: 800; color: #0f172a; }
.dh-sub { margin-top: 6px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dh-relation { font-size: 15px; color: #475569; }
.dh-tabs { display: flex; gap: 8px; }

.sec-card {
  background: #fafafa; border: 1px solid #f1f5f9;
  border-radius: 12px; padding: 14px 18px; margin-bottom: 16px;
}
.sec-title { font-size: 18px; font-weight: 700; color: #1f2937; margin: 0 0 8px; }
.sec-sub { font-size: 13px; color: #6b7280; font-weight: 500; }
.kv-list { margin: 0; padding: 0; }
.kv {
  display: grid; grid-template-columns: 110px 1fr;
  padding: 9px 0; border-bottom: 1px dashed #e5e7eb;
  font-size: 16px;
}
.kv:last-child { border-bottom: none; }
.kv dt { color: #6b7280; font-weight: 600; }
.kv dd { margin: 0; color: #111827; word-break: break-all; }
.tag-mg { margin: 3px 6px 3px 0; }
.muted { color: #9ca3af; }

.drawer-actions {
  display: flex; justify-content: flex-end; gap: 10px;
  margin: 24px 2px 8px;
}

/* ===== AI 占位卡 ===== */
.drawer-ai-panel-placeholder { padding: 10px 6px; }
.ph-head { display: flex; align-items: center; gap: 10px; font-size: 18px; font-weight: 700; }
.ph-emoji { font-size: 22px; }
.ph-body { text-align: center; padding: 30px 20px; }
.ph-big-emoji { font-size: 72px; line-height: 1; margin-bottom: 14px; }
.ph-h { font-size: 20px; font-weight: 700; margin: 0 0 10px; color: #1f2937; }
.ph-p { color: #475569; font-size: 15px; line-height: 1.85; margin: 0 auto 22px; max-width: 380px; }

/* ===== 移动端：外层 AppLayout 已给 12px 内边距，这里收窄避免双重挤压 ===== */
@media (max-width: 768px) {
  .person-profile-view { padding: 8px 0 24px; }
  .top-bar { padding: 12px; gap: 10px; border-radius: 12px; }
  .page-title { font-size: 22px; }
  .icon { font-size: 26px; }
  /* 操作区改为纵向：搜索框占满，按钮并排 */
  .top-actions { flex-wrap: wrap; justify-content: stretch; }
  .search-input { width: 100%; flex: 1 1 100%; }
  /* 卡片网格窄屏单列 */
  .profile-grid { grid-template-columns: 1fr; gap: 12px; }
  /* KV 列表标签列收窄，值列多留空间 */
  .kv { grid-template-columns: 84px 1fr; font-size: 15px; }
}
</style>
