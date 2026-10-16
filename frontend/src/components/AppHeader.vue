<template>
  <div class="app-header" role="banner" aria-label="页面头部：家庭切换 + 安全退出">
    <div class="header-left">
      <span class="brand">家庭助手</span>
    </div>
    <div class="header-center">
      <span class="label">当前家庭：</span>
      <el-select
        v-model="currentId"
        :key="selectKey"
        placeholder="请选择家庭"
        size="large"
        :style="isMobile ? 'width: 120px' : 'width: 220px'"
        aria-label="切换当前家庭"
        @change="onFamilyChange"
      >
        <el-option
          v-for="f in familyOptions"
          :key="f.id"
          :label="f.name"
          :value="String(f.id)"
        />
      </el-select>
    </div>
    <div class="header-right">
      <span v-if="!isMobile" class="nickname" aria-hidden="true">{{ nickname }}</span>
      <div v-if="!isMobile" class="font-scale-wrap">
        <span class="sr-only">切换页面字号：当前字号{{ fontStore.scaleLabel }}</span>
        <el-radio-group
          v-model="fontScaleModel"
          class="font-scale-radio"
          size="large"
          role="radiogroup"
          aria-label="切换页面字号，三档：标准、大、特大"
          @change="(val) => fontStore.setScale(val)"
        >
          <el-radio-button value="S" aria-label="标准字号 十六像素">🔠<span class="btn-text">&nbsp;标准</span></el-radio-button>
          <el-radio-button value="M" aria-label="大字号 十八像素 推荐老人使用">A<span class="btn-text">&nbsp;大</span></el-radio-button>
          <el-radio-button value="L" aria-label="特大字号 二十一像素 老花眼适用">🅰️<span class="btn-text">&nbsp;特大</span></el-radio-button>
        </el-radio-group>
      </div>
      <el-button size="large" type="danger" @click="onLogout"><span aria-hidden="true">🚪</span> {{ isMobile ? '退出' : '安全退出' }}</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { useFamilyStore } from '../stores/family'
import { useFontStore } from '../stores/font'
import { familyApi } from '../api/family'
import { authApi } from '../api/auth'
import { useIsMobile } from '../composables/useIsMobile'

const router = useRouter()
const authStore = useAuthStore()
const familyStore = useFamilyStore()
const fontStore = useFontStore()
const isMobile = useIsMobile()

const familyOptions = computed(() => authStore.families || [])
// currentId 用 ref + watch：el-select 对 computed v-model 的匹配有时不及时，
// 改用 ref 直接绑定，watch 同步 currentFamilyId 变化。
const currentId = ref(familyStore.currentFamilyId || '')
watch(
  () => familyStore.currentFamilyId,
  (val) => { currentId.value = val || '' }
)
// 当家庭列表从空变为有值时，强制 el-select 重新渲染以正确匹配 label
const selectKey = computed(() => familyOptions.value.length)

const nickname = computed(() => {
  const info = authStore.userInfo || {}
  return info.nickname || info.email || '家人'
})

const fontScaleModel = computed({
  get: () => fontStore.currentScale,
  set: (val) => fontStore.setScale(val)
})

let familySwitchVersion = 0
async function onFamilyChange(id) {
  const version = ++familySwitchVersion
  try {
    const detail = await familyApi.getDetail(id)
    if (version !== familySwitchVersion) return
    familyStore.setCurrent(detail)
    ElMessage.success('已切换到「' + (detail && detail.name ? detail.name : '当前家庭') + '」')
  } catch (e) {
    if (version !== familySwitchVersion) return
    // 切换失败：回滚到 currentFamilyId（watch 不触发时需手动恢复）
    currentId.value = familyStore.currentFamilyId || ''
  }
}

async function onLogout() {
  try {
    await ElMessageBox.confirm('确认要安全退出登录吗？下次回来需要再输入账号和密码哦。', '安全退出', {
      confirmButtonText: '确认退出',
      cancelButtonText: '我再想想',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await authApi.logout()
  } catch (e) { /* 后端登出失败不阻断本地登出 */ }
  authStore.logout()
  familyStore.clear()
  ElMessage.success('已安全退出，欢迎下次再回来')
  router.push('/login')
}

</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  height: 64px;
  padding: 0 20px;
  gap: 16px;
}
.header-left {
  flex: 0 0 auto;
}
.brand {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}
.header-center {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.label {
  font-size: 16px;
  color: #606266;
}
.header-right {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.nickname {
  font-size: 16px;
  color: #606266;
}
/* 移动端适配：窄屏紧凑布局，375px 屏不溢出
   预算：左右边距20 + 品牌68 + 下拉120 + 退出按钮≈90 + gap ≈ 310px < 375px */
@media (max-width: 768px) {
  .app-header {
    padding: 0 10px;
    gap: 8px;
  }
  .brand {
    font-size: 17px;
    white-space: nowrap;
  }
  /* 家庭选择框允许随剩余宽度收缩，特大字号下也不挤出退出按钮。 */
  .header-center {
    min-width: 0;
  }
  .header-center :deep(.el-select) {
    max-width: 100%;
  }
  /* 隐藏"当前家庭："文字标签，省出约 78px；下拉框自带 aria-label 不影响无障碍 */
  .label {
    display: none;
  }
  .header-right {
    gap: 6px;
  }
  /* 退出按钮收窄内边距，避免大号按钮挤占下拉框空间 */
  .header-right :deep(.el-button) {
    padding: 0 12px;
  }
}
</style>
