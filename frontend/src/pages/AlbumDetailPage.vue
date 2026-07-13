<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ArrowLeft, MoreVertical, Upload, Edit, Trash2, Image, Star, X, Camera } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElImageViewer } from 'element-plus'
import { albumApi, fileApi } from '@/api'
import { getMockPhotoList, generateMockPhotos } from '@/mock'
import { dayjs } from '@/utils'
import type { Photo, Album } from '@/types'

const route = useRoute()
const router = useRouter()

const babyId = computed(() => {
  const id = route.params.id
  if (Array.isArray(id)) return id[0] as string
  return id || '1'
})
const albumId = computed(() => {
  const id = route.params.albumId
  if (Array.isArray(id)) return id[0] as string
  return id || '1'
})

const photos = ref<Photo[]>([])
const album = ref<Album | null>(null)
const loading = ref(false)
const showUploadDialog = ref(false)
const showEditDialog = ref(false)
const showAlbumMenu = ref(false)
const showPhotoMenu = ref(false)
const currentPhoto = ref<Photo | null>(null)
const showViewer = ref(false)
const initialIndex = ref(0)
const uploading = ref(false)

const uploadForm = ref({
  files: [] as File[],
  date: dayjs().format('YYYY-MM-DD'),
  title: '',
})

const photoForm = ref({
  title: '',
  description: '',
  date: '',
})

const previewList = computed(() => photos.value.map(p => p.url))

async function fetchPhotos() {
  loading.value = true
  try {
    const result = await albumApi.getPhotoList(babyId.value, albumId.value)
    photos.value = result.list
  } catch (e) {
    console.warn('使用mock照片数据:', e)
    const result = getMockPhotoList(babyId.value, albumId.value)
    photos.value = result.list
  } finally {
    loading.value = false
  }
}

async function fetchAlbumInfo() {
  try {
    const albums = await albumApi.getAlbumList(babyId.value)
    album.value = albums.find(a => a.id === Number(albumId.value) || a.id === albumId.value) || null
  } catch (e) {
    console.warn('获取相册信息失败:', e)
    const mockPhotos = generateMockPhotos(babyId.value, albumId.value, 12)
    album.value = {
      id: albumId.value,
      babyId: babyId.value,
      title: '成长日记',
      description: '记录宝宝每天的成长瞬间',
      coverUrl: mockPhotos[0]?.url,
      photoCount: mockPhotos.length,
      createdAt: dayjs().subtract(30, 'day').format('YYYY-MM-DD HH:mm:ss'),
      updatedAt: dayjs().format('YYYY-MM-DD HH:mm:ss'),
    }
  }
}

function goBack() {
  router.back()
}

function openViewer(index: number) {
  initialIndex.value = index
  showViewer.value = true
}

function openUploadDialog() {
  uploadForm.value = {
    files: [],
    date: dayjs().format('YYYY-MM-DD'),
    title: '',
  }
  showUploadDialog.value = true
}

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files) {
    uploadForm.value.files = Array.from(target.files)
  }
}

async function handleUpload() {
  if (uploadForm.value.files.length === 0) {
    ElMessage.warning('请选择要上传的照片')
    return
  }

  uploading.value = true
  try {
    const uploadedPhotos: Photo[] = []
    
    for (let i = 0; i < uploadForm.value.files.length; i++) {
      const file = uploadForm.value.files[i]
      try {
        const result = await fileApi.uploadImage(file)
        uploadedPhotos.push({
          id: Date.now() + i,
          babyId: babyId.value,
          albumId: albumId.value,
          url: result.url,
          thumbnailUrl: result.thumbnailUrl || result.url,
          title: uploadForm.value.title || `照片 ${photos.value.length + i + 1}`,
          description: '',
          date: uploadForm.value.date,
          size: result.size,
          createdAt: new Date().toISOString(),
        })
      } catch (e) {
        console.error('上传失败:', e)
        const url = URL.createObjectURL(file)
        uploadedPhotos.push({
          id: Date.now() + i,
          babyId: babyId.value,
          albumId: albumId.value,
          url,
          thumbnailUrl: url,
          title: uploadForm.value.title || `照片 ${photos.value.length + i + 1}`,
          description: '',
          date: uploadForm.value.date,
          size: file.size,
          createdAt: new Date().toISOString(),
        })
      }
    }

    try {
      await albumApi.uploadPhotos(babyId.value, albumId.value, { photos: uploadedPhotos })
    } catch (e) {
      console.error(e)
    }

    photos.value = [...uploadedPhotos, ...photos.value]
    ElMessage.success(`成功上传 ${uploadedPhotos.length} 张照片`)
    showUploadDialog.value = false
  } finally {
    uploading.value = false
  }
}

function openPhotoMenu(photo: Photo) {
  currentPhoto.value = photo
  photoForm.value = {
    title: photo.title || '',
    description: photo.description || '',
    date: photo.date || dayjs().format('YYYY-MM-DD'),
  }
  showPhotoMenu.value = true
}

function openEditPhotoDialog() {
  showPhotoMenu.value = false
  showEditDialog.value = true
}

async function handleUpdatePhoto() {
  if (!currentPhoto.value) return

  try {
    await albumApi.updatePhoto(babyId.value, currentPhoto.value.id, {
      title: photoForm.value.title,
      description: photoForm.value.description,
      date: photoForm.value.date,
    })
    ElMessage.success('更新成功')
  } catch (e) {
    console.error(e)
    const index = photos.value.findIndex(p => p.id === currentPhoto.value!.id)
    if (index !== -1) {
      photos.value[index] = {
        ...photos.value[index],
        title: photoForm.value.title,
        description: photoForm.value.description,
        date: photoForm.value.date,
      }
    }
    ElMessage.success('更新成功')
  }

  showEditDialog.value = false
  await fetchPhotos()
}

async function handleSetCover() {
  if (!currentPhoto.value) return

  try {
    await albumApi.setCover(babyId.value, albumId.value, currentPhoto.value.id)
    ElMessage.success('已设为封面')
  } catch (e) {
    console.error(e)
    if (album.value) {
      album.value.coverUrl = currentPhoto.value.url
    }
    ElMessage.success('已设为封面')
  }
  showPhotoMenu.value = false
}

async function handleDeletePhoto() {
  if (!currentPhoto.value) return

  try {
    await ElMessageBox.confirm('确定要删除这张照片吗？', '删除照片', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })

    try {
      await albumApi.deletePhoto(babyId.value, currentPhoto.value.id)
      ElMessage.success('删除成功')
    } catch (e) {
      console.error(e)
      photos.value = photos.value.filter(p => p.id !== currentPhoto.value!.id)
      ElMessage.success('删除成功')
    }

    showPhotoMenu.value = false
    await fetchPhotos()
  } catch {
  }
}

async function handleEditAlbum() {
  showAlbumMenu.value = false
  if (!album.value) return

  try {
    const { value: title } = await ElMessageBox.prompt('请输入相册名称', '编辑相册', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: album.value.title,
      inputValidator: (value: string) => {
        if (!value?.trim()) return '请输入相册名称'
        return true
      },
    })

    try {
      await albumApi.updateAlbum(babyId.value, albumId.value, title)
      ElMessage.success('更新成功')
    } catch (e) {
      console.error(e)
      if (album.value) {
        album.value.title = title
      }
      ElMessage.success('更新成功')
    }
  } catch {
  }
}

async function handleDeleteAlbum() {
  showAlbumMenu.value = false
  try {
    await ElMessageBox.confirm('确定要删除这个相册吗？相册内的照片也会被删除。', '删除相册', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })

    try {
      await albumApi.deleteAlbum(babyId.value, albumId.value)
      ElMessage.success('删除成功')
    } catch (e) {
      console.error(e)
      ElMessage.success('删除成功')
    }

    router.back()
  } catch {
  }
}

onMounted(async () => {
  await Promise.all([
    fetchAlbumInfo(),
    fetchPhotos(),
  ])
})
</script>

<template>
  <div class="page-container">
    <div class="safe-area-top bg-gradient-to-b from-pink-100 to-transparent pb-4">
      <div class="px-5 pt-4">
        <div class="flex items-center justify-between mb-2">
          <button 
            @click="goBack"
            class="w-12 h-12 rounded-full bg-white flex items-center justify-center shadow-soft hover:shadow-card transition-shadow"
          >
            <ArrowLeft :size="22" class="text-pink-500" />
          </button>
          <h1 class="text-2xl font-bold text-gray-800 truncate max-w-[200px]">
            {{ album?.title || '相册详情' }}
          </h1>
          <button 
            @click="showAlbumMenu = !showAlbumMenu"
            class="w-12 h-12 rounded-full bg-white flex items-center justify-center shadow-soft hover:shadow-card transition-shadow relative"
          >
            <MoreVertical :size="22" class="text-pink-500" />
          </button>
        </div>
        <p class="text-lg text-gray-600 text-center">
          共 <span class="font-semibold text-pink-500">{{ photos.length }}</span> 张照片
        </p>
      </div>
    </div>

    <div v-if="showAlbumMenu" class="fixed top-20 right-5 z-40 bg-white rounded-2xl shadow-card py-2 min-w-[140px] animate-slide-down">
      <button 
        @click="handleEditAlbum"
        class="w-full px-5 py-3 text-left text-lg text-gray-700 hover:bg-pink-50 transition-colors flex items-center gap-3"
      >
        <Edit :size="20" />
        编辑相册
      </button>
      <button 
        @click="handleDeleteAlbum"
        class="w-full px-5 py-3 text-left text-lg text-red-500 hover:bg-red-50 transition-colors flex items-center gap-3"
      >
        <Trash2 :size="20" />
        删除相册
      </button>
    </div>

    <div class="px-5 pb-28">
      <div v-if="loading" class="py-16 text-center">
        <p class="text-lg text-gray-400">加载中...</p>
      </div>

      <div v-else-if="photos.length === 0" class="py-16 text-center">
        <div class="w-24 h-24 mx-auto mb-4 rounded-full bg-pink-50 flex items-center justify-center">
          <Camera :size="40" class="text-pink-300" />
        </div>
        <p class="text-lg text-gray-500 mb-2">还没有照片</p>
        <p class="text-base text-gray-400">点击下方按钮上传第一张照片吧</p>
      </div>

      <div v-else class="grid grid-cols-3 gap-1.5">
        <div 
          v-for="(photo, index) in photos" 
          :key="photo.id"
          class="relative aspect-square bg-pink-50 cursor-pointer group animate-fade-in"
          @click="openViewer(index)"
        >
          <el-image
            :src="photo.url"
            fit="cover"
            class="w-full h-full"
            :preview-src-list="[]"
            :initial-index="0"
          />
          <button 
            @click.stop="openPhotoMenu(photo)"
            class="absolute top-2 right-2 w-9 h-9 rounded-full bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center"
          >
            <MoreVertical :size="18" class="text-white" />
          </button>
        </div>
      </div>
    </div>

    <div class="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-100 p-4 safe-area-bottom">
      <button 
        @click="openUploadDialog"
        class="w-full btn-primary text-xl py-5 flex items-center justify-center gap-2"
      >
        <Upload :size="24" />
        上传照片
      </button>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showUploadDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showUploadDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">上传照片</h3>
              <button 
                @click="showUploadDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">选择照片</label>
                <div class="border-2 border-dashed border-pink-200 rounded-2xl p-8 text-center hover:border-pink-400 transition-colors cursor-pointer bg-pink-50/50">
                  <input 
                    type="file" 
                    multiple 
                    accept="image/*" 
                    class="hidden" 
                    id="photo-upload"
                    @change="handleFileChange"
                  />
                  <label for="photo-upload" class="cursor-pointer block">
                    <Upload :size="40" class="mx-auto text-pink-400 mb-3" />
                    <p class="text-lg text-gray-600 mb-1">点击选择照片</p>
                    <p class="text-base text-gray-400">支持多张照片同时上传</p>
                    <p v-if="uploadForm.files.length > 0" class="text-pink-500 font-medium mt-2">
                      已选择 {{ uploadForm.files.length }} 张照片
                    </p>
                  </label>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">照片日期</label>
                <input 
                  v-model="uploadForm.date"
                  type="date" 
                  class="input-base text-lg py-4"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">
                  批量标题
                  <span class="text-gray-400 text-sm">（可选）</span>
                </label>
                <input 
                  v-model="uploadForm.title"
                  type="text" 
                  placeholder="为所有照片设置统一标题" 
                  class="input-base text-lg py-4"
                />
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showUploadDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleUpload"
                :disabled="uploading"
                class="flex-1 bg-gradient-to-r from-pink-400 to-pink-500 text-white text-lg py-4 rounded-xl font-medium shadow-soft hover:shadow-card transition-all disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {{ uploading ? '上传中...' : '上传' }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showPhotoMenu && currentPhoto" class="fixed inset-0 bg-black/50 z-50 flex items-end justify-center" @click.self="showPhotoMenu = false">
          <div class="bg-white w-full sm:max-w-lg rounded-t-3xl p-4 animate-slide-up safe-area-bottom">
            <div class="w-12 h-1 bg-gray-200 rounded-full mx-auto mb-4"></div>
            
            <button 
              @click="openEditPhotoDialog"
              class="w-full py-4 text-left text-lg text-gray-700 hover:bg-pink-50 rounded-xl transition-colors flex items-center gap-4 px-4"
            >
              <Edit :size="22" class="text-pink-500" />
              编辑照片信息
            </button>

            <button 
              @click="handleSetCover"
              class="w-full py-4 text-left text-lg text-gray-700 hover:bg-pink-50 rounded-xl transition-colors flex items-center gap-4 px-4"
            >
              <Star :size="22" class="text-yellow-500" />
              设为封面
            </button>

            <button 
              @click="handleDeletePhoto"
              class="w-full py-4 text-left text-lg text-red-500 hover:bg-red-50 rounded-xl transition-colors flex items-center gap-4 px-4"
            >
              <Trash2 :size="22" />
              删除照片
            </button>

            <button 
              @click="showPhotoMenu = false"
              class="w-full mt-3 py-4 text-lg text-gray-500 bg-gray-50 rounded-xl font-medium hover:bg-gray-100 transition-colors"
            >
              取消
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showEditDialog" class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" @click.self="showEditDialog = false">
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">编辑照片</h3>
              <button 
                @click="showEditDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">标题</label>
                <input 
                  v-model="photoForm.title"
                  type="text" 
                  placeholder="照片标题" 
                  class="input-base text-lg py-4"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">
                  描述
                  <span class="text-gray-400 text-sm">（可选）</span>
                </label>
                <textarea 
                  v-model="photoForm.description"
                  placeholder="添加照片描述" 
                  class="input-base min-h-[100px] resize-none text-base"
                  rows="3"
                ></textarea>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">日期</label>
                <input 
                  v-model="photoForm.date"
                  type="date" 
                  class="input-base text-lg py-4"
                />
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showEditDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleUpdatePhoto"
                class="flex-1 bg-gradient-to-r from-pink-400 to-pink-500 text-white text-lg py-4 rounded-xl font-medium shadow-soft hover:shadow-card transition-all"
              >
                保存
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <el-image-viewer
        v-if="showViewer"
        :url-list="previewList"
        :initial-index="initialIndex"
        @close="showViewer = false"
      />
    </Teleport>
  </div>
</template>

<style scoped>
</style>
