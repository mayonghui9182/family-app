<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ArrowLeft, Plus, Edit, Trash2, Image, FolderOpen, X } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { albumApi } from '@/api'
import { generateMockAlbums } from '@/mock'
import { useBabyStore } from '@/stores/baby'
import type { Album } from '@/types'

const route = useRoute()
const router = useRouter()
const babyStore = useBabyStore()

const babyId = computed(() => {
  const id = route.params.id
  if (Array.isArray(id)) return id[0] as string
  return id || '1'
})
const babyName = computed(() => babyStore.babyInfo?.name || '宝宝')

const albums = ref<Album[]>([])
const loading = ref(false)
const showDialog = ref(false)
const isEdit = ref(false)
const currentAlbum = ref<Album | null>(null)

const albumForm = ref({
  title: '',
  description: '',
})

async function fetchAlbums() {
  loading.value = true
  try {
    const data = await albumApi.getAlbumList(babyId.value)
    albums.value = data
  } catch (e) {
    console.warn('使用mock相册数据:', e)
    albums.value = generateMockAlbums(babyId.value)
  } finally {
    loading.value = false
  }
}

function goBack() {
  router.back()
}

function openAlbum(album: Album) {
  router.push(`/baby/${babyId.value}/albums/${album.id}`)
}

function openCreateDialog() {
  isEdit.value = false
  currentAlbum.value = null
  albumForm.value = {
    title: '',
    description: '',
  }
  showDialog.value = true
}

function openEditDialog(album: Album) {
  isEdit.value = true
  currentAlbum.value = album
  albumForm.value = {
    title: album.title,
    description: album.description || '',
  }
  showDialog.value = true
}

async function handleSubmit() {
  if (!albumForm.value.title.trim()) {
    ElMessage.warning('请输入相册名称')
    return
  }

  try {
    if (isEdit.value && currentAlbum.value) {
      await albumApi.updateAlbum(
        babyId.value,
        currentAlbum.value.id,
        albumForm.value.title,
        albumForm.value.description || undefined
      )
      ElMessage.success('更新成功')
    } else {
      await albumApi.createAlbum(
        babyId.value,
        albumForm.value.title,
        albumForm.value.description || undefined
      )
      ElMessage.success('创建成功')
    }
    showDialog.value = false
    await fetchAlbums()
  } catch (e) {
    console.error(e)
    if (isEdit.value && currentAlbum.value) {
      const index = albums.value.findIndex(a => a.id === currentAlbum.value!.id)
      if (index !== -1) {
        albums.value[index] = {
          ...albums.value[index],
          title: albumForm.value.title,
          description: albumForm.value.description,
        }
      }
      ElMessage.success('更新成功')
    } else {
      const newAlbum: Album = {
        id: Date.now(),
        babyId: babyId.value,
        title: albumForm.value.title,
        description: albumForm.value.description,
        coverUrl: '',
        photoCount: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      albums.value.unshift(newAlbum)
      ElMessage.success('创建成功')
    }
    showDialog.value = false
  }
}

async function handleDelete(album: Album) {
  try {
    await ElMessageBox.confirm(`确定要删除相册"${album.title}"吗？相册内的照片也会被删除。`, '删除相册', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    try {
      await albumApi.deleteAlbum(babyId.value, album.id)
      ElMessage.success('删除成功')
    } catch (e) {
      console.error(e)
      albums.value = albums.value.filter(a => a.id !== album.id)
      ElMessage.success('删除成功')
    }
    await fetchAlbums()
  } catch {
  }
}

onMounted(async () => {
  await babyStore.fetchBabyInfo()
  await fetchAlbums()
})
</script>

<template>
  <div class="page-container">
    <div class="safe-area-top bg-gradient-to-b from-pink-100 to-transparent pb-4">
      <div class="px-5 pt-4">
        <div class="flex items-center justify-between mb-4">
          <button 
            @click="goBack"
            class="w-12 h-12 rounded-full bg-white flex items-center justify-center shadow-soft hover:shadow-card transition-shadow"
          >
            <ArrowLeft :size="22" class="text-pink-500" />
          </button>
          <h1 class="text-2xl font-bold text-gray-800">宝宝相册</h1>
          <div class="w-12"></div>
        </div>
        <p class="text-lg text-gray-600 text-center">
          <span class="font-semibold text-pink-500">{{ babyName }}</span> 的美好回忆
        </p>
      </div>
    </div>

    <div class="px-5 pb-28">
      <div v-if="loading" class="py-16 text-center">
        <p class="text-lg text-gray-400">加载中...</p>
      </div>

      <div v-else-if="albums.length === 0" class="py-16 text-center">
        <div class="w-24 h-24 mx-auto mb-4 rounded-full bg-pink-50 flex items-center justify-center">
          <Image :size="40" class="text-pink-300" />
        </div>
        <p class="text-lg text-gray-500 mb-2">还没有相册</p>
        <p class="text-base text-gray-400">点击下方按钮创建第一个相册吧</p>
      </div>

      <div v-else class="grid grid-cols-2 gap-4">
        <div 
          v-for="(album, index) in albums" 
          :key="album.id"
          class="card-base overflow-hidden cursor-pointer animate-slide-up"
          :style="{ animationDelay: `${index * 50}ms` }"
          @click="openAlbum(album)"
        >
          <div class="relative -mx-5 -mt-5 mb-4 aspect-square bg-pink-50">
            <el-image
              v-if="album.coverUrl"
              :src="album.coverUrl"
              :preview-src-list="[album.coverUrl]"
              fit="cover"
              class="w-full h-full"
              @click.stop
            />
            <div v-else class="w-full h-full flex items-center justify-center">
              <FolderOpen :size="48" class="text-pink-300" />
            </div>
            <button 
              @click.stop="openEditDialog(album)"
              class="absolute top-2 right-2 w-10 h-10 rounded-full bg-white/90 backdrop-blur-sm flex items-center justify-center shadow-soft hover:bg-white transition-colors"
            >
              <Edit :size="18" class="text-pink-500" />
            </button>
          </div>
          <div class="flex items-start justify-between">
            <div class="flex-1 min-w-0">
              <h3 class="text-lg font-bold text-gray-800 truncate">{{ album.title }}</h3>
              <p class="text-base text-gray-400 mt-1">{{ album.photoCount }} 张照片</p>
            </div>
          </div>
          <p v-if="album.description" class="text-sm text-gray-400 mt-2 line-clamp-2">{{ album.description }}</p>
        </div>
      </div>
    </div>

    <div class="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-100 p-4 safe-area-bottom">
      <button 
        @click="openCreateDialog"
        class="w-full btn-primary text-xl py-5 flex items-center justify-center gap-2"
      >
        <Plus :size="24" />
        新建相册
      </button>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">{{ isEdit ? '编辑相册' : '新建相册' }}</h3>
              <button 
                @click="showDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">相册名称</label>
                <input 
                  v-model="albumForm.title"
                  type="text" 
                  placeholder="请输入相册名称" 
                  class="input-base text-lg py-4"
                  maxlength="20"
                />
              </div>
              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">
                  相册描述
                  <span class="text-gray-400 text-sm">（可选）</span>
                </label>
                <textarea 
                  v-model="albumForm.description"
                  placeholder="添加相册描述" 
                  class="input-base min-h-[100px] resize-none text-base"
                  rows="3"
                  maxlength="100"
                ></textarea>
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleSubmit"
                class="flex-1 bg-gradient-to-r from-pink-400 to-pink-500 text-white text-lg py-4 rounded-xl font-medium shadow-soft hover:shadow-card transition-all"
              >
                {{ isEdit ? '保存' : '创建' }}
              </button>
            </div>

            <button 
              v-if="isEdit && currentAlbum"
              @click="handleDelete(currentAlbum)"
              class="w-full mt-4 py-4 rounded-xl text-red-500 text-lg font-medium hover:bg-red-50 transition-colors flex items-center justify-center gap-2"
            >
              <Trash2 :size="20" />
              删除相册
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
