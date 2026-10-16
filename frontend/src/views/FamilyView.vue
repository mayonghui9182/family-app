<template>
  <el-card class="family-card">
    <template #header>
      <span class="title">🏠 家庭管理</span>
    </template>

    <!-- 家庭列表 -->
    <div v-if="!detailMode" role="region" aria-label="我的家庭列表">
      <div class="toolbar">
        <el-button type="primary" size="large" @click="openCreate">➕ 创建家庭</el-button>
        <el-button type="success" size="large" @click="openJoin">📨 用邀请码加入</el-button>
        <el-button size="large" :loading="loading" @click="loadFamilies">🔄 刷新</el-button>
      </div>
      <el-table :data="families" v-loading="loading" stripe size="large" role="table" aria-label="我加入的所有家庭列表：名称/我的角色/成员数/操作">
        <el-table-column label="家庭名称" min-width="200">
          <template #default="{ row }">
            <span v-if="row.avatar" class="fam-avatar"><img :src="row.avatar" alt="" role="presentation" /></span>
            <span class="fam-name">{{ row.name }}</span>
            <el-tag v-if="String(row.id) === currentFamilyId" type="success" size="small" style="margin-left:8px" aria-label="当前正在使用的家庭">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="我的角色" width="140" align="center">
          <template #default="{ row }">{{ roleText(row.role) }}</template>
        </el-table-column>
        <el-table-column label="成员数" width="100" align="center">
          <template #default="{ row }">{{ memberCountOf(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="360" align="center">
          <template #default="{ row }">
            <el-button size="large" :aria-label="`把家庭${row.name}设为当前家庭`" @click="selectAsCurrent(row)">✅ 设为当前</el-button>
            <el-button size="large" type="primary" :aria-label="`查看家庭${row.name}的详情`" @click="openDetail(row)">👁️ 详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && families.length === 0" description="还没有家庭，可以创建家庭，或用家人分享的邀请码加入" />
    </div>

    <!-- 家庭详情 -->
    <div v-else role="region" aria-label="当前家庭详情：基本信息 + 成员">
      <div class="toolbar">
        <el-button size="large" @click="backToList">← 返回家庭列表</el-button>
        <el-button size="large" type="success" @click="openInvite">📨 邀请家人</el-button>
      </div>

      <el-descriptions :title="detail.name" :column="1" border size="large" role="group" aria-label="家庭基本信息卡片">
        <el-descriptions-item label="家庭名称">{{ detail.name }}</el-descriptions-item>
        <el-descriptions-item label="简介">{{ detail.description || '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="我的角色">{{ roleText(detail.role) }}</el-descriptions-item>
      </el-descriptions>

      <div v-if="detail.role === 'OWNER'" class="section-actions">
        <el-button size="large" @click="openEditFamily">✏️ 编辑家庭信息</el-button>
        <el-button v-if="members.length <= 1" size="large" type="warning" aria-label="退出只有自己一个人的家庭并清理该家庭数据" @click="handleLeave">🚪 退出并清理空家庭</el-button>
        <el-button size="large" type="danger" aria-label="永久解散这个家庭，所有任务分类和家人成员都会被清理" @click="handleRemove">⚠️ 解散这个家庭</el-button>
      </div>

      <h3 class="section-title"><span aria-hidden="true">👨‍👩‍👧‍👦 </span>家庭成员</h3>
      <el-table :data="members" v-loading="detailLoading" stripe size="large" role="table" aria-label="家庭成员列表：昵称/角色/加入时间/操作">
        <el-table-column label="昵称" min-width="180">
          <template #default="{ row }">
            <span v-if="row.avatar" class="fam-avatar"><img :src="row.avatar" alt="" role="presentation" /></span>
            {{ row.nickname || row.username || ('用户#' + row.id) }}
          </template>
        </el-table-column>
        <el-table-column label="角色" width="140" align="center">
          <template #default="{ row }">{{ roleText(row.role) }}</template>
        </el-table-column>
        <el-table-column label="加入时间" width="200" align="center">
          <template #default="{ row }">{{ formatDate(row.joinedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="420" align="center">
          <template #default="{ row }">
            <el-button v-if="canManage && row.role !== 'OWNER'" size="large" :aria-label="`切换家人${row.nickname || ''}的角色，当前是${roleText(row.role)}`" @click="handleToggleRole(row)">{{ row.role === 'ADULT' ? '👶 设为孩子' : '👨 设为成人' }}</el-button>
            <el-button v-if="canManage && row.role !== 'OWNER'" size="large" type="warning" :aria-label="`把管理员转让给家人${row.nickname || ''}`" @click="openTransfer(row)">👑 转让管理员</el-button>
            <el-button v-if="canManage && row.role !== 'OWNER'" size="large" type="danger" :aria-label="`把家人${row.nickname || ''}踢出家庭`" @click="handleKick(row)">🗑️ 踢出家庭</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!detailLoading && members.length === 0" description="家里还没有家人呢，点【📨 邀请家人】发邀请码让家人加入吧" aria-label="家里还没有家人，点邀请家人按钮发邀请码让家人加入" />

      <div v-if="detail.role !== 'OWNER'" class="section-actions">
        <el-button size="large" type="danger" aria-label="退出当前家庭，将无法访问该家庭的任务和分类" @click="handleLeave">🚪 退出家庭</el-button>
      </div>
    </div>

    <!-- 创建家庭 -->
    <el-dialog v-model="createVisible" title="创建家庭" width="460px">
      <el-form label-width="90px">
        <el-form-item label="家庭名称" required>
          <el-input v-model="createForm.name" size="large" placeholder="如：温馨之家" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="createForm.description" type="textarea" :rows="3" size="large" />
        </el-form-item>
        <el-form-item label="头像URL">
          <el-input v-model="createForm.avatar" size="large" placeholder="选填，图片网址" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="large" @click="createVisible = false">取消</el-button>
        <el-button type="primary" size="large" :loading="creating" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="joinVisible" title="用邀请码加入家庭" width="460px">
      <p class="join-tip">请向家人索取 6 位邀请码。加入后可以切换查看这个家庭的任务。</p>
      <el-input v-model="joinCode" size="large" maxlength="6" placeholder="输入 6 位邀请码" aria-label="输入 6 位邀请码加入家庭" @input="onJoinCodeInput" @keyup.enter="handleJoin" />
      <template #footer>
        <el-button size="large" @click="joinVisible = false">取消</el-button>
        <el-button type="primary" size="large" :loading="joining" @click="handleJoin">加入家庭</el-button>
      </template>
    </el-dialog>

    <!-- 编辑家庭 -->
    <el-dialog v-model="editVisible" title="编辑家庭信息" width="460px">
      <el-form label-width="90px">
        <el-form-item label="家庭名称">
          <el-input v-model="editForm.name" size="large" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="editForm.description" type="textarea" :rows="3" size="large" />
        </el-form-item>
        <el-form-item label="头像URL">
          <el-input v-model="editForm.avatar" size="large" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="large" @click="editVisible = false">取消</el-button>
        <el-button type="primary" size="large" :loading="saving" @click="handleSaveFamily">保存</el-button>
      </template>
    </el-dialog>

    <!-- 转让管理员 -->
    <el-dialog v-model="transferVisible" title="转让家庭管理员" width="420px">
      <p style="line-height:1.8;font-size:15px">
        将管理员转让给「<b>{{ transferTarget?.nickname || transferTarget?.username || ('用户#' + (transferTarget?.id ?? '')) }}</b>」？<br />
        转让后，你将变为成人成员。
      </p>
      <template #footer>
        <el-button size="large" @click="transferVisible = false">取消</el-button>
        <el-button type="warning" size="large" :loading="transferring" @click="confirmTransfer">确认转让</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { familyApi } from '../api/family'
import { inviteApi } from '../api/invite'
import { useFamilyStore } from '../stores/family'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const familyStore = useFamilyStore()
const authStore = useAuthStore()
const currentFamilyId = computed(() => familyStore.currentFamilyId)

const families = ref([])
const loading = ref(false)

const detailMode = ref(false)
const detail = ref({})
const members = ref([])
const detailLoading = ref(false)
const canManage = computed(() => detail.value.role === 'OWNER')

const createVisible = ref(false)
const createForm = reactive({ name: '', description: '', avatar: '' })
const creating = ref(false)
const joinVisible = ref(false)
const joinCode = ref('')
const joining = ref(false)

const editVisible = ref(false)
const editForm = reactive({ name: '', description: '', avatar: '' })
const saving = ref(false)

const transferVisible = ref(false)
const transferTarget = ref(null)
const transferring = ref(false)

function roleText(role) {
  if (role === 'OWNER') return '家庭管理员'
  if (role === 'ADULT') return '成人成员'
  if (role === 'CHILD') return '孩子成员'
  return role || '-'
}
function memberCountOf(fam) {
  if (typeof fam.memberCount === 'number') return fam.memberCount
  if (Array.isArray(fam.members)) return fam.members.length
  return '-'
}
function formatDate(s) {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}
function userIdOf(row) {
  return row.id ?? row.userId
}
function userLabel(row) {
  return row.nickname || row.username || ('用户#' + userIdOf(row))
}

async function loadFamilies() {
  loading.value = true
  try {
    families.value = (await familyApi.list()) || []
    authStore.setFamilies(families.value)
    familyStore.reconcile(families.value)
  } finally {
    loading.value = false
  }
}

function selectAsCurrent(fam) {
  familyStore.setCurrent(fam)
  ElMessage.success(`已切换到家庭：${fam.name}`)
}

async function openDetail(fam) {
  detailMode.value = true
  await loadDetail(fam.id)
}

async function loadDetail(id) {
  detailLoading.value = true
  try {
    const data = await familyApi.getDetail(id)
    members.value = (data && data.members) || []
    const ownMember = members.value.find(member => String(member.userId) === String(authStore.userInfo?.userId))
    detail.value = { ...data, role: ownMember?.role || families.value.find(family => String(family.id) === String(id))?.role }
  } finally {
    detailLoading.value = false
  }
}

function backToList() {
  detailMode.value = false
  detail.value = {}
  members.value = []
}

function openCreate() {
  createForm.name = ''
  createForm.description = ''
  createForm.avatar = ''
  createVisible.value = true
}

function openJoin() {
  joinCode.value = ''
  joinVisible.value = true
}

function onJoinCodeInput(value) {
  joinCode.value = (value || '').toUpperCase().replace(/[^A-Z0-9]/g, '')
}

async function handleJoin() {
  const code = joinCode.value.trim()
  if (code.length !== 6) {
    ElMessage.warning('请输入 6 位邀请码')
    return
  }
  joining.value = true
  try {
    await inviteApi.accept({ code })
    joinVisible.value = false
    await loadFamilies()
    ElMessage.success('已加入家庭，请选择要使用的家庭')
  } finally {
    joining.value = false
  }
}

async function handleCreate() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请输入家庭名称')
    return
  }
  creating.value = true
  try {
    await familyApi.create({
      name: createForm.name.trim(),
      description: createForm.description,
      avatar: createForm.avatar
    })
    ElMessage.success('家庭已创建')
    createVisible.value = false
    await loadFamilies()
  } finally {
    creating.value = false
  }
}

function openEditFamily() {
  editForm.name = detail.value.name || ''
  editForm.description = detail.value.description || ''
  editForm.avatar = detail.value.avatar || ''
  editVisible.value = true
}

async function handleSaveFamily() {
  if (!editForm.name.trim()) {
    ElMessage.warning('请输入家庭名称')
    return
  }
  saving.value = true
  try {
    await familyApi.update(detail.value.id, {
      name: editForm.name.trim(),
      description: editForm.description,
      avatar: editForm.avatar
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await loadDetail(detail.value.id)
    await loadFamilies()
  } finally {
    saving.value = false
  }
}

async function handleToggleRole(row) {
  const next = row.role === 'ADULT' ? 'CHILD' : 'ADULT'
  try {
    await ElMessageBox.confirm(`确认将「${userLabel(row)}」的角色改为${roleText(next)}吗？`, '修改角色', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await familyApi.changeRole(detail.value.id, userIdOf(row), { role: next })
    ElMessage.success('角色已更新')
    await loadDetail(detail.value.id)
    await loadFamilies()
  } catch (e) {
    /* 失败由拦截器提示 */
  }
}

async function handleKick(row) {
  try {
    await ElMessageBox.confirm(`确认将「${userLabel(row)}」踢出家庭吗？此操作不可撤销。`, '踢出成员', {
      confirmButtonText: '确认踢出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await familyApi.kick(detail.value.id, userIdOf(row))
    ElMessage.success('已踢出该成员')
    await loadDetail(detail.value.id)
    await loadFamilies()
  } catch (e) {
    /* 失败由拦截器提示 */
  }
}

function openTransfer(row) {
  transferTarget.value = row
  transferVisible.value = true
}

async function confirmTransfer() {
  if (!transferTarget.value) return
  try {
    await ElMessageBox.confirm(
      '确认把「' + (detail.value.name || '') + '」的管理员权限转让给「' + userLabel(transferTarget.value) + '」吗？\n\n转让后您就不再是家庭管理员啦。',
      '🏠 管理员转让确认',
      { confirmButtonText: '确认转让', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) { return }
  transferring.value = true
  try {
    await familyApi.transferOwner(detail.value.id, { newOwnerId: userIdOf(transferTarget.value) })
    ElMessage.success('管理员已转让')
    transferVisible.value = false
    await loadDetail(detail.value.id)
    await loadFamilies()
  } finally {
    transferring.value = false
  }
}

async function handleRemove() {
  if (!detail.value || !detail.value.id) return
  try {
    await ElMessageBox.confirm(
      '解散家庭后：该家庭所有任务和分类都会被隐藏，家人也会被清出此家庭，且暂时不能恢复。\n\n确定要解散「' + (detail.value.name || '') + '」吗？',
      '⚠️ 解散家庭（不可恢复）',
      {
        confirmButtonText: '我确定要解散',
        cancelButtonText: '再想想',
        type: 'error',
        dangerouslyUseHTMLString: false
      }
    )
  } catch (e) { return }
  try {
    await familyApi.remove(detail.value.id)
    forgetFamily(detail.value.id)
    ElMessage.success('家庭已解散')
    detailMode.value = false
    await loadFamilies()
  } catch (e) { /* 拦截器提示 */ }
}

async function handleLeave() {
  try {
    const ownerOnly = detail.value.role === 'OWNER'
    await ElMessageBox.confirm(ownerOnly
      ? '这个家庭只有您一位成员。退出后，家庭、任务和相关资料会被永久清理。确认继续吗？'
      : '确认退出该家庭吗？退出后将无法访问该家庭的任务和分类。', ownerOnly ? '退出并清理空家庭' : '退出家庭', {
      confirmButtonText: '确认退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await familyApi.leave(detail.value.id)
    ElMessage.success('已退出家庭')
    forgetFamily(detail.value.id)
    backToList()
    await loadFamilies()
  } catch (e) {
    /* 失败由拦截器提示 */
  }
}

function openInvite() {
  router.push(`/families/${detail.value.id}/invites`)
}

// 删除或退出成功即更新本地选择，即使随后的列表刷新失败也不再使用旧家庭。
function forgetFamily(id) {
  families.value = families.value.filter(family => String(family.id) !== String(id))
  authStore.setFamilies(families.value)
  familyStore.reconcile(families.value)
}

onMounted(loadFamilies)
</script>

<style scoped>
.family-card {
  max-width: 1000px;
  margin: 0 auto;
}
.title {
  font-size: 20px;
  font-weight: 600;
}
.toolbar {
  margin-bottom: 16px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.section-title {
  margin: 20px 0 12px;
  font-size: 18px;
}
.section-actions {
  margin: 16px 0;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.fam-avatar {
  display: inline-block;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  overflow: hidden;
  vertical-align: middle;
  margin-right: 8px;
}
.fam-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.fam-name {
  font-weight: 600;
  vertical-align: middle;
}
.join-tip {
  font-size: 17px;
  line-height: 1.7;
  margin: 0 0 16px;
}
</style>
