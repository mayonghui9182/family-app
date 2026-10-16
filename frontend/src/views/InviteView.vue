<template>
  <el-card class="invite-card" role="region" aria-label="邀请家人：接受邀请 + 创建邀请码 + 我创建的邀请列表">
    <template #header>
      <div class="card-header">
        <span class="title">📨 邀请家人</span>
        <el-button size="large" aria-label="返回家庭管理页面" @click="$router.push('/families')">← 返回家庭管理</el-button>
      </div>
    </template>

    <!-- 接受邀请 -->
    <el-alert
      title="把邀请码告诉家人，他们在下方输入邀请码后就能加入家庭"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />
    <el-form :inline="true" class="accept-form" @submit.prevent>
      <el-form-item label="邀请码">
        <el-input
          v-model="acceptCode"
          placeholder="输入6位邀请码"
          size="large"
          maxlength="6"
          style="width: 260px; font-size: 22px; letter-spacing: 4px"
          aria-label="输入6位大写字母邀请码加入家庭"
          @input="onCodeInput"
          @keyup.enter="handleAccept"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" size="large" :loading="accepting" aria-label="确认输入的6位邀请码加入对应家庭" @click="handleAccept">✅ 加入这个家庭</el-button>
      </el-form-item>
    </el-form>

    <el-divider />

    <!-- 创建邀请 -->
    <h3 class="section-title">📤 创建新邀请</h3>
    <el-form :inline="true" class="create-form" @submit.prevent>
      <el-form-item label="最大可用次数">
        <el-input-number v-model="createForm.maxUses" :min="1" :max="100" size="large" aria-label="设置邀请码最多可被多少人使用，1到100次" />
      </el-form-item>
      <el-form-item label="有效天数">
        <el-input-number v-model="createForm.ttlDays" :min="1" :max="30" size="large" aria-label="设置邀请码有效天数，1到30天" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" size="large" :loading="creating" aria-label="创建新的邀请码给家人使用" @click="handleCreate">➕ 创建邀请</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="lastCreatedCode"
      type="success"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
      role="status"
    >
      <div style="font-size:16px;line-height:1.8">
        新邀请码：<strong :aria-label="`邀请码：${lastCreatedCode}`"><b style="font-size:24px;letter-spacing:3px">{{ lastCreatedCode }}</b></strong>
        <el-button size="small" type="primary" link aria-label="复制新生成的邀请码到剪贴板" @click="copyCode(lastCreatedCode)">复制</el-button>
      </div>
    </el-alert>

    <!-- 邀请列表 -->
    <h3 class="section-title">📋 我创建的邀请</h3>
    <el-table :data="invites" v-loading="loading" stripe size="large" role="table" aria-label="我创建的邀请列表：邀请码/可用次数/过期时间/状态/操作">
      <el-table-column label="邀请码" min-width="180">
        <template #default="{ row }">
          <strong :aria-label="`邀请码：${row.code}`"><span class="code-text">{{ row.code }}</span></strong>
          <el-button size="small" link :aria-label="`复制邀请码${row.code}到剪贴板`" @click="copyCode(row.code)">复制</el-button>
        </template>
      </el-table-column>
      <el-table-column label="可用次数" width="130" align="center">
        <template #default="{ row }">{{ row.usedCount || 0 }} / {{ row.maxUses }}</template>
      </el-table-column>
      <el-table-column label="过期时间" width="180" align="center">
        <template #default="{ row }">{{ formatDate(row.expiresAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="large" :aria-label="`邀请状态：${statusText(row.status)}`">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" align="center">
        <template #default="{ row }">
          <el-button v-if="row.status === 'ACTIVE'" size="large" type="danger" :aria-label="`作废邀请码${row.code}`" @click="handleCancel(row)">💥 作废邀请码</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && invites.length === 0" description="还没有创建过邀请哦，点上方【➕ 创建邀请】生成邀请码给家人" aria-label="还没有创建过邀请，点创建邀请按钮生成邀请码" />

    <!-- 分享卡操作区（邀请码展示下方） -->
    <div class="share-card-entry">
      <div class="sr-only" id="shareCardDesc">生成邀请卡图片，可保存到相册发给家人，扫码就能加入</div>
      <div class="entry-actions">
        <el-button
          size="large"
          type="primary"
          plain
          aria-describedby="shareCardDesc"
          aria-label="预览分享卡图片，可查看二维码和大号邀请码"
          :loading="shareCardBusy"
          @click="onPreviewShare"
        >📷 预览分享卡</el-button>
        <el-button
          size="large"
          type="success"
          aria-describedby="shareCardDesc"
          aria-label="保存分享卡图片到本地设备，可通过微信发给家人"
          :loading="shareCardBusy"
          @click="onSaveShare"
        >💾 保存图片</el-button>
      </div>
      <p class="entry-tip" aria-hidden="true">💡 家人微信扫一扫卡片上的二维码，就能加入你家</p>
    </div>
  </el-card>

  <!-- 分享卡预览 Dialog -->
  <el-dialog
    v-model="shareCardVisible"
    class="share-card-dialog"
    role="dialog"
    aria-label="邀请分享卡预览，包含家庭二维码和大号邀请码"
    width="90%"
    top="5vh"
    destroy-on-close
  >
    <img
      v-if="previewShareImg"
      class="share-card-preview"
      :src="previewShareImg"
      :alt="`邀请加入${currentFamilyName}家庭的分享卡，二维码和邀请码${currentInviteCode}`"
    />
    <div class="share-card-tip" v-if="previewShareImg">
      <strong aria-live="polite">👇 保存方法</strong><br/>
      电脑：右键图片 → 「图片另存为」<br/>
      手机/微信：长按图片 → 「保存到相册」
    </div>
    <template #footer>
      <div class="share-card-actions">
        <el-button
          size="large"
          type="primary"
          aria-label="把当前分享卡图片保存到本地文件"
          :loading="shareCardBusy"
          @click="onSaveShare"
        >💾 保存图片到本地</el-button>
        <el-button size="large" @click="shareCardVisible = false" aria-label="关闭预览弹窗">关闭</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inviteApi } from '../api/invite'
import { useFamilyStore } from '../stores/family'
import { useAuthStore } from '../stores/auth'
import { generateShareCard } from '../utils/shareCard'

const route = useRoute()
const router = useRouter()
const familyStore = useFamilyStore()
const authStore = useAuthStore()
const familyId = computed(() => route.params.id)

const acceptCode = ref('')
const accepting = ref(false)

const invites = ref([])
const loading = ref(false)

const createForm = reactive({ maxUses: 1, ttlDays: 7 })
const creating = ref(false)
const lastCreatedCode = ref('')

const shareCardVisible = ref(false)
const previewShareImg = ref('')
const shareCardBusy = ref(false)

const currentInviteCode = computed(() => {
  if (lastCreatedCode.value) return lastCreatedCode.value
  const active = (invites.value || []).find(r => r.status === 'ACTIVE')
  if (active && active.code) return active.code
  return route.query.code || ''
})
const currentFamilyName = computed(() => (familyStore.currentFamily && familyStore.currentFamily.name) || '我的家庭')
const currentUserName = computed(() => (authStore.userInfo && (authStore.userInfo.nickname || authStore.userInfo.name)) || '')

function onCodeInput(val) {
  acceptCode.value = (val || '').toUpperCase().replace(/[^A-Z0-9]/g, '')
}

async function loadInvites() {
  if (!familyId.value) return
  loading.value = true
  try {
    invites.value = (await inviteApi.list(familyId.value)) || []
  } finally {
    loading.value = false
  }
}

async function handleAccept() {
  const code = acceptCode.value.trim()
  if (code.length !== 6) {
    ElMessage.warning('请输入 6 位邀请码')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认用您的当前账号加入这个家庭吗？加入后就可以看到这个家庭的任务、分类啦。',
      '✅ 加入家庭确认',
      { confirmButtonText: '确认加入', cancelButtonText: '再检查一下', type: 'warning' }
    )
  } catch (e) { return }
  accepting.value = true
  try {
    await inviteApi.accept({ code })
    ElMessage.success('已成功加入家庭')
    acceptCode.value = ''
    router.push('/families')
  } finally {
    accepting.value = false
  }
}

async function handleCreate() {
  if (!familyId.value) {
    ElMessage.error('缺少家庭信息')
    return
  }
  creating.value = true
  try {
    const data = await inviteApi.create(familyId.value, {
      maxUses: createForm.maxUses,
      ttlDays: createForm.ttlDays
    })
    ElMessage.success('邀请已创建')
    lastCreatedCode.value = (data && data.code) || ''
    previewShareImg.value = ''
    await loadInvites()
  } finally {
    creating.value = false
  }
}

async function handleCancel(row) {
  try {
    await ElMessageBox.confirm(`确认取消邀请「${row.code}」吗？取消后该邀请码将无法使用。`, '取消邀请', {
      confirmButtonText: '确认取消',
      cancelButtonText: '保留',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await inviteApi.cancel(familyId.value, row.id)
    ElMessage.success('邀请已取消')
    await loadInvites()
  } catch (e) {
    /* 失败由拦截器提示 */
  }
}

async function copyCode(code) {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success('邀请码已复制')
  } catch (e) {
    ElMessage.warning('复制失败，请手动复制：' + code)
  }
}

async function ensureShareCardImg() {
  if (previewShareImg.value) return previewShareImg.value
  if (!currentInviteCode.value) {
    ElMessage.warning('当前没有邀请码，请先创建邀请码')
    return ''
  }
  shareCardBusy.value = true
  try {
    previewShareImg.value = await generateShareCard({
      code: currentInviteCode.value,
      familyName: currentFamilyName.value,
      userName: currentUserName.value
    })
    return previewShareImg.value
  } catch (e) {
    console.error('[shareCard] 生成失败:', e)
    ElMessage.error('生成分享卡失败：' + (e && e.message ? e.message : '未知错误'))
    return ''
  } finally {
    shareCardBusy.value = false
  }
}

async function onPreviewShare() {
  const ok = await ensureShareCardImg()
  if (!ok) return
  shareCardVisible.value = true
}

async function onSaveShare() {
  const dataURL = await ensureShareCardImg()
  if (!dataURL) return
  const isIOS = /iPhone|iPad|iPod/i.test(navigator.userAgent)
  if (isIOS) {
    const win = window.open('', '_blank')
    if (win) {
      win.document.write(`<!doctype html><title>邀请加入${currentFamilyName.value}家庭</title><body style="margin:0;background:#F3F4F6;text-align:center;padding:16px;"><p style="font-size:18px;color:#1F2937;">长按下方图片 → <b>保存到相册</b></p><img src="${dataURL}" style="width:100%;max-width:500px;border-radius:14px;box-shadow:0 12px 40px rgba(37,99,235,.18);"/></body></html>`)
      win.document.close()
      if (shareCardVisible.value) shareCardVisible.value = false
    }
    return
  }
  const a = document.createElement('a')
  a.href = dataURL
  const filename = `邀请加入-${currentFamilyName.value}-${currentInviteCode.value}.png`
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  ElMessage.success('图片已保存：' + filename)
  if (shareCardVisible.value) shareCardVisible.value = false
}

function statusText(status) {
  const map = { ACTIVE: '有效', USED: '已用完', EXPIRED: '已过期', CANCELED: '已取消' }
  return map[status] || status || '-'
}
function statusType(status) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'EXPIRED' || status === 'CANCELED') return 'info'
  return 'warning'
}
function formatDate(s) {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd} ${hh}:${mi}`
}

onMounted(loadInvites)
</script>

<style scoped>
.invite-card {
  max-width: 1000px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-header .title {
  font-size: 20px;
  font-weight: 600;
}
.accept-form {
  margin-bottom: 8px;
}
.section-title {
  margin: 16px 0 12px;
  font-size: 18px;
}
.code-text {
  font-weight: 700;
  letter-spacing: 2px;
  margin-right: 8px;
}
.share-card-entry {
  margin: 32px 0 8px;
  padding: 22px 24px;
  border: 2px dashed #BFDBFE;
  border-radius: 16px;
  background: #EFF6FF;
}
.share-card-entry .entry-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  justify-content: center;
}
.share-card-entry .entry-actions .el-button {
  min-width: 200px;
  min-height: 48px;
  font-size: 1.05rem;
}
.share-card-entry .entry-tip {
  margin: 14px 0 0;
  text-align: center;
  color: #1D4ED8;
  font-size: 1.0rem;
  font-weight: 500;
}
</style>