<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Home, Users, UserPlus, KeyRound, ArrowRight } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const authStore = useAuthStore()

const activeTab = ref<'create' | 'join'>('create')

const createForm = ref({
  familyName: '',
  userName: '',
})

const joinForm = ref({
  inviteCode: '',
  userName: '',
})

const handleCreateFamily = async () => {
  if (!createForm.value.familyName.trim()) {
    ElMessage.warning('请输入家庭名称')
    return
  }
  if (!createForm.value.userName.trim()) {
    ElMessage.warning('请输入您的称呼')
    return
  }
  try {
    await authStore.createFamily(createForm.value.familyName, createForm.value.userName)
    ElMessage.success('家庭创建成功！')
    router.push('/')
  } catch (e) {
    console.error(e)
    ElMessage.error('创建失败，请重试')
  }
}

const handleJoinFamily = async () => {
  if (!joinForm.value.inviteCode.trim()) {
    ElMessage.warning('请输入邀请码')
    return
  }
  if (!joinForm.value.userName.trim()) {
    ElMessage.warning('请输入您的称呼')
    return
  }
  try {
    await authStore.joinFamily(joinForm.value.inviteCode, joinForm.value.userName)
    ElMessage.success('加入家庭成功！')
    router.push('/')
  } catch (e) {
    console.error(e)
    ElMessage.error('加入失败，请检查邀请码是否正确')
  }
}
</script>

<template>
  <div class="min-h-screen bg-gradient-warm flex flex-col">
    <div class="flex-1 flex flex-col items-center justify-center px-6 py-12">
      <div class="w-full max-w-md animate-fade-in">
        <div class="text-center mb-10">
          <div class="w-20 h-20 mx-auto mb-5 rounded-3xl bg-gradient-primary flex items-center justify-center shadow-float">
            <Home :size="40" class="text-white" />
          </div>
          <h1 class="text-3xl font-bold text-gray-800 mb-2">温馨家庭</h1>
          <p class="text-lg text-gray-500">让家人更亲近，让生活更美好</p>
        </div>

        <div class="bg-white rounded-3xl shadow-card p-6">
          <div class="flex gap-2 bg-cream-100 p-1.5 rounded-2xl mb-8">
            <button
              @click="activeTab = 'create'"
              :class="[
                'flex-1 py-4 rounded-xl text-lg font-semibold transition-all flex items-center justify-center gap-2',
                activeTab === 'create'
                  ? 'bg-white text-primary-600 shadow-soft'
                  : 'text-gray-500 hover:text-gray-700'
              ]"
            >
              <Users :size="22" />
              创建家庭
            </button>
            <button
              @click="activeTab = 'join'"
              :class="[
                'flex-1 py-4 rounded-xl text-lg font-semibold transition-all flex items-center justify-center gap-2',
                activeTab === 'join'
                  ? 'bg-white text-primary-600 shadow-soft'
                  : 'text-gray-500 hover:text-gray-700'
              ]"
            >
              <UserPlus :size="22" />
              加入家庭
            </button>
          </div>

          <div v-show="activeTab === 'create'" class="space-y-6 animate-fade-in">
            <div>
              <label class="block text-lg font-medium text-gray-700 mb-3">
                <Users :size="20" class="inline mr-2 text-primary-500" />
                家庭名称
              </label>
              <input
                v-model="createForm.familyName"
                type="text"
                placeholder="请输入家庭名称，如：快乐一家人"
                class="input-base text-lg py-4"
              />
            </div>

            <div>
              <label class="block text-lg font-medium text-gray-700 mb-3">
                <KeyRound :size="20" class="inline mr-2 text-primary-500" />
                您的称呼
              </label>
              <input
                v-model="createForm.userName"
                type="text"
                placeholder="请输入您的称呼，如：爸爸、妈妈"
                class="input-base text-lg py-4"
              />
            </div>

            <button
              @click="handleCreateFamily"
              :disabled="authStore.loading"
              class="w-full btn-primary text-lg py-5 flex items-center justify-center gap-2 disabled:opacity-50"
            >
              <span v-if="authStore.loading">创建中...</span>
              <template v-else>
                创建家庭
                <ArrowRight :size="22" />
              </template>
            </button>
          </div>

          <div v-show="activeTab === 'join'" class="space-y-6 animate-fade-in">
            <div>
              <label class="block text-lg font-medium text-gray-700 mb-3">
                <KeyRound :size="20" class="inline mr-2 text-primary-500" />
                家庭邀请码
              </label>
              <input
                v-model="joinForm.inviteCode"
                type="text"
                placeholder="请输入6位邀请码"
                class="input-base text-lg py-4 text-center tracking-widest font-mono"
                maxlength="6"
              />
            </div>

            <div>
              <label class="block text-lg font-medium text-gray-700 mb-3">
                <Users :size="20" class="inline mr-2 text-primary-500" />
                您的称呼
              </label>
              <input
                v-model="joinForm.userName"
                type="text"
                placeholder="请输入您的称呼，如：爷爷、奶奶"
                class="input-base text-lg py-4"
              />
            </div>

            <button
              @click="handleJoinFamily"
              :disabled="authStore.loading"
              class="w-full btn-primary text-lg py-5 flex items-center justify-center gap-2 disabled:opacity-50"
            >
              <span v-if="authStore.loading">加入中...</span>
              <template v-else>
                加入家庭
                <ArrowRight :size="22" />
              </template>
            </button>
          </div>
        </div>

        <p class="text-center text-gray-400 text-base mt-8">
          温馨提示：创建或加入家庭后即可开始使用
        </p>
      </div>
    </div>
  </div>
</template>
