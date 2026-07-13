<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { 
  Package, 
  Plus, 
  Minus, 
  AlertTriangle, 
  ArrowLeft, 
  Search,
  X,
  PackageOpen,
  Calendar
} from 'lucide-vue-next'
import { useItemStore } from '@/stores/item'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dayjs } from '@/utils'
import type { HouseholdItem } from '@/types'

const router = useRouter()
const itemStore = useItemStore()

const searchKeyword = ref('')
const activeCategory = ref('all')

const showAddDialog = ref(false)
const showStockInDialog = ref(false)
const showStockOutDialog = ref(false)
const isEditing = ref(false)
const currentItem = ref<HouseholdItem | null>(null)

const categories = [
  { key: 'all', label: '全部', icon: '📦' },
  { key: 'diaper', label: '尿不湿', icon: '👶' },
  { key: 'milk', label: '奶粉', icon: '🍼' },
  { key: 'food', label: '食品', icon: '🍎' },
  { key: 'daily', label: '日用品', icon: '🧴' },
  { key: 'medicine', label: '药品', icon: '💊' },
  { key: 'other', label: '其他', icon: '📋' },
]

const unitOptions = ['个', '包', '袋', '瓶', '盒', '罐', '片', '支']
const sourceOptions = ['购买', '赠送', '其他']
const purposeOptions = ['使用', '赠送', '丢弃']
const iconOptions = ['📦', '👶', '🍼', '🍎', '🧴', '💊', '🥣', '🧻', '🩲', '🛁', '🤒', '🍪', '🥛', '🧸', '🎀']

const formData = ref({
  name: '',
  category: 'other',
  unit: '个',
  totalQuantity: 0,
  warningQuantity: 0,
  icon: '📦',
  remark: '',
})

const stockInForm = ref({
  quantity: 1,
  recordDate: dayjs().format('YYYY-MM-DD'),
  source: '购买',
  price: undefined as number | undefined,
  remark: '',
})

const stockOutForm = ref({
  quantity: 1,
  recordDate: dayjs().format('YYYY-MM-DD'),
  purpose: '使用',
  remark: '',
})

onMounted(async () => {
  await Promise.all([
    itemStore.fetchItems(),
    itemStore.fetchStats(),
  ])
})

const filteredItems = computed(() => {
  let items = itemStore.items
  if (activeCategory.value && activeCategory.value !== 'all') {
    items = items.filter(item => item.category === activeCategory.value)
  }
  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase()
    items = items.filter(item => 
      item.name.toLowerCase().includes(keyword)
    )
  }
  return items
})

const handleCategoryClick = (key: string) => {
  activeCategory.value = key
}

const openAddDialog = () => {
  isEditing.value = false
  formData.value = {
    name: '',
    category: 'other',
    unit: '个',
    totalQuantity: 0,
    warningQuantity: 0,
    icon: '📦',
    remark: '',
  }
  showAddDialog.value = true
}

const openEditDialog = (item: HouseholdItem) => {
  isEditing.value = true
  currentItem.value = item
  formData.value = {
    name: item.name,
    category: item.category,
    unit: item.unit,
    totalQuantity: item.totalQuantity,
    warningQuantity: item.warningQuantity,
    icon: item.icon || '📦',
    remark: item.remark || '',
  }
  showAddDialog.value = true
}

const openStockInDialog = (item: HouseholdItem) => {
  currentItem.value = item
  stockInForm.value = {
    quantity: 1,
    recordDate: dayjs().format('YYYY-MM-DD'),
    source: '购买',
    price: undefined,
    remark: '',
  }
  showStockInDialog.value = true
}

const openStockOutDialog = (item: HouseholdItem) => {
  currentItem.value = item
  stockOutForm.value = {
    quantity: 1,
    recordDate: dayjs().format('YYYY-MM-DD'),
    purpose: '使用',
    remark: '',
  }
  showStockOutDialog.value = true
}

const handleSubmitItem = async () => {
  if (!formData.value.name.trim()) {
    ElMessage.warning('请输入物品名称')
    return
  }
  if (formData.value.totalQuantity < 0) {
    ElMessage.warning('库存数量不能为负数')
    return
  }
  if (formData.value.warningQuantity < 0) {
    ElMessage.warning('预警数量不能为负数')
    return
  }
  try {
    if (isEditing.value && currentItem.value) {
      await itemStore.updateItem(currentItem.value.id, formData.value)
      ElMessage.success('修改成功')
    } else {
      await itemStore.createItem(formData.value)
      ElMessage.success('添加成功')
    }
    showAddDialog.value = false
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const handleStockIn = async () => {
  if (!currentItem.value) return
  if (!stockInForm.value.quantity || stockInForm.value.quantity <= 0) {
    ElMessage.warning('请输入正确的入库数量')
    return
  }
  try {
    await itemStore.stockIn(currentItem.value.id, stockInForm.value)
    ElMessage.success('入库成功')
    showStockInDialog.value = false
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const handleStockOut = async () => {
  if (!currentItem.value) return
  if (!stockOutForm.value.quantity || stockOutForm.value.quantity <= 0) {
    ElMessage.warning('请输入正确的出库数量')
    return
  }
  if (stockOutForm.value.quantity > currentItem.value.totalQuantity) {
    ElMessage.warning('出库数量不能超过当前库存')
    return
  }
  try {
    await itemStore.stockOut(currentItem.value.id, stockOutForm.value)
    ElMessage.success('出库成功')
    showStockOutDialog.value = false
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const goToDetail = (item: HouseholdItem) => {
  router.push(`/items/${item.id}`)
}

const goBack = () => {
  router.back()
}
</script>

<template>
  <div class="page-container pb-28">
    <div class="safe-area-top bg-gradient-to-b from-green-50 to-transparent pb-4">
      <div class="px-5 pt-4">
        <div class="flex items-center gap-4 mb-6">
          <button 
            @click="goBack"
            class="w-10 h-10 rounded-full bg-white shadow-md flex items-center justify-center hover:bg-gray-50 transition-colors"
          >
            <ArrowLeft :size="22" class="text-gray-600" />
          </button>
          <h1 class="text-2xl font-bold text-gray-800 flex-1">物品库存</h1>
        </div>

        <div class="relative mb-6">
          <Search :size="20" class="absolute left-4 top-1/2 -translate-y-1/2 text-gray-400" />
          <input 
            v-model="searchKeyword"
            type="text" 
            placeholder="搜索物品名称..." 
            class="w-full pl-12 pr-12 py-4 rounded-2xl bg-white shadow-md text-lg focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
          />
          <button 
            v-if="searchKeyword"
            @click="searchKeyword = ''"
            class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
          >
            <X :size="20" />
          </button>
        </div>

        <div class="grid grid-cols-2 gap-4 mb-6">
          <div class="bg-white rounded-2xl shadow-md p-5">
            <div class="flex items-center gap-3 mb-2">
              <div class="w-12 h-12 rounded-xl bg-green-100 flex items-center justify-center">
                <Package :size="24" class="text-green-500" />
              </div>
              <div>
                <p class="text-3xl font-bold text-gray-800">{{ itemStore.stats.totalItems }}</p>
                <p class="text-base text-gray-500">物品总数</p>
              </div>
            </div>
          </div>
          <div class="bg-white rounded-2xl shadow-md p-5">
            <div class="flex items-center gap-3 mb-2">
              <div class="w-12 h-12 rounded-xl bg-red-100 flex items-center justify-center">
                <AlertTriangle :size="24" class="text-red-500" />
              </div>
              <div>
                <p class="text-3xl font-bold text-red-500">{{ itemStore.stats.lowStockItems }}</p>
                <p class="text-base text-gray-500">低库存</p>
              </div>
            </div>
          </div>
        </div>

        <div class="overflow-x-auto -mx-5 px-5 pb-2">
          <div class="flex gap-2 min-w-max">
            <button
              v-for="cat in categories"
              :key="cat.key"
              @click="handleCategoryClick(cat.key)"
              :class="[
                'flex items-center gap-2 px-5 py-3 rounded-xl text-base font-medium transition-all whitespace-nowrap',
                activeCategory === cat.key
                  ? 'bg-gradient-to-r from-green-400 to-green-500 text-white shadow-md'
                  : 'bg-white text-gray-600 hover:bg-gray-50 shadow-sm'
              ]"
            >
              <span class="text-xl">{{ cat.icon }}</span>
              <span>{{ cat.label }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <div class="px-5 pt-2">
      <div v-if="filteredItems.length > 0" class="grid grid-cols-2 gap-4 pb-6">
        <div 
          v-for="(item, index) in filteredItems" 
          :key="item.id"
          class="bg-white rounded-2xl shadow-md overflow-hidden animate-slide-up"
          :style="{ animationDelay: `${index * 50}ms` }"
        >
          <div 
            class="p-4 cursor-pointer hover:bg-gray-50 transition-colors"
            @click="goToDetail(item)"
          >
            <div class="flex items-start justify-between mb-3">
              <div class="text-4xl">{{ item.icon }}</div>
              <div 
                v-if="item.isLowStock"
                class="flex items-center gap-1 px-2 py-1 rounded-full bg-red-50"
              >
                <AlertTriangle :size="14" class="text-red-500" />
                <span class="text-xs font-medium text-red-500">预警</span>
              </div>
            </div>
            <h3 class="text-lg font-bold text-gray-800 mb-1 line-clamp-1">{{ item.name }}</h3>
            <p class="text-sm text-gray-400 mb-3">{{ item.categoryName }}</p>
            <div class="flex items-baseline gap-1">
              <span 
                :class="[
                  'text-3xl font-bold',
                  item.isLowStock ? 'text-red-500' : 'text-gray-800'
                ]"
              >
                {{ item.totalQuantity }}
              </span>
              <span class="text-base text-gray-500">{{ item.unit }}</span>
            </div>
            <p class="text-sm text-gray-400 mt-1">
              预警: {{ item.warningQuantity }}{{ item.unit }}
            </p>
          </div>
          <div class="flex border-t border-gray-100">
            <button 
              @click.stop="openStockOutDialog(item)"
              class="flex-1 py-4 bg-red-50 hover:bg-red-100 text-red-500 font-bold text-lg transition-colors flex items-center justify-center gap-2"
            >
              <Minus :size="22" />
              <span>使用</span>
            </button>
            <div class="w-px bg-gray-100"></div>
            <button 
              @click.stop="openStockInDialog(item)"
              class="flex-1 py-4 bg-green-50 hover:bg-green-100 text-green-500 font-bold text-lg transition-colors flex items-center justify-center gap-2"
            >
              <Plus :size="22" />
              <span>录入</span>
            </button>
          </div>
        </div>
      </div>

      <div v-else class="text-center py-16">
        <div class="text-6xl mb-4">📦</div>
        <p class="text-gray-500 text-lg mb-2">暂无物品</p>
        <p class="text-gray-400 text-base">点击下方按钮添加新物品</p>
      </div>
    </div>

    <div class="fixed bottom-0 left-0 right-0 p-5 safe-area-bottom bg-gradient-to-t from-app via-app to-transparent pt-10">
      <button 
        @click="openAddDialog"
        class="w-full py-5 rounded-2xl bg-gradient-to-r from-green-400 to-green-500 text-white text-xl font-bold shadow-lg hover:shadow-xl transition-all active:scale-98 flex items-center justify-center gap-2"
      >
        <Plus :size="26" />
        <span>新增物品</span>
      </button>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div 
          v-if="showAddDialog" 
          class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" 
          @click.self="showAddDialog = false"
        >
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">
                {{ isEditing ? '编辑物品' : '新增物品' }}
              </h3>
              <button 
                @click="showAddDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">物品图标</label>
                <div class="flex flex-wrap gap-2">
                  <button
                    v-for="icon in iconOptions"
                    :key="icon"
                    @click="formData.icon = icon"
                    :class="[
                      'w-14 h-14 rounded-xl text-2xl transition-all flex items-center justify-center',
                      formData.icon === icon 
                        ? 'bg-green-100 ring-2 ring-green-400' 
                        : 'bg-gray-50 hover:bg-gray-100'
                    ]"
                  >
                    {{ icon }}
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">物品名称</label>
                <input 
                  v-model="formData.name"
                  type="text" 
                  placeholder="请输入物品名称" 
                  class="w-full px-4 py-4 rounded-xl bg-gray-50 text-lg focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">分类</label>
                <div class="flex flex-wrap gap-2">
                  <button
                    v-for="cat in categories.filter(c => c.key !== 'all')"
                    :key="cat.key"
                    @click="formData.category = cat.key"
                    :class="[
                      'px-4 py-3 rounded-xl text-base font-medium transition-all flex items-center gap-2',
                      formData.category === cat.key 
                        ? 'bg-green-100 text-green-600 ring-2 ring-green-200' 
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    <span class="text-xl">{{ cat.icon }}</span>
                    <span>{{ cat.label }}</span>
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">单位</label>
                <div class="flex flex-wrap gap-2">
                  <button
                    v-for="unit in unitOptions"
                    :key="unit"
                    @click="formData.unit = unit"
                    :class="[
                      'px-5 py-3 rounded-xl text-base font-medium transition-all',
                      formData.unit === unit 
                        ? 'bg-green-100 text-green-600 ring-2 ring-green-200' 
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    {{ unit }}
                  </button>
                </div>
              </div>

              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">初始库存</label>
                  <input 
                    v-model.number="formData.totalQuantity"
                    type="number" 
                    min="0"
                    placeholder="0" 
                    class="w-full px-4 py-4 rounded-xl bg-gray-50 text-lg text-center focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
                  />
                </div>
                <div>
                  <label class="block text-base font-medium text-gray-700 mb-3">预警数量</label>
                  <input 
                    v-model.number="formData.warningQuantity"
                    type="number" 
                    min="0"
                    placeholder="0" 
                    class="w-full px-4 py-4 rounded-xl bg-gray-50 text-lg text-center focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
                  />
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">备注</label>
                <textarea 
                  v-model="formData.remark"
                  placeholder="添加备注（可选）" 
                  class="w-full px-4 py-4 rounded-xl bg-gray-50 text-base min-h-[80px] resize-none focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
                  rows="2"
                ></textarea>
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showAddDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleSubmitItem"
                class="flex-1 py-4 rounded-xl bg-gradient-to-r from-green-400 to-green-500 text-white text-lg font-medium hover:shadow-lg transition-all"
              >
                {{ isEditing ? '保存' : '添加' }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="fade">
        <div 
          v-if="showStockInDialog" 
          class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" 
          @click.self="showStockInDialog = false"
        >
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">
                入库 - {{ currentItem?.name }}
              </h3>
              <button 
                @click="showStockInDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div class="text-center py-6 bg-green-50 rounded-2xl">
                <p class="text-gray-500 text-base mb-2">当前库存</p>
                <p class="text-4xl font-bold text-gray-800">
                  {{ currentItem?.totalQuantity }}
                  <span class="text-xl font-normal text-gray-500">{{ currentItem?.unit }}</span>
                </p>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">入库数量</label>
                <div class="flex items-center gap-4">
                  <button 
                    @click="stockInForm.quantity = Math.max(1, stockInForm.quantity - 1)"
                    class="w-14 h-14 rounded-xl bg-gray-100 flex items-center justify-center text-2xl font-bold text-gray-600 hover:bg-gray-200 transition-colors"
                  >
                    -
                  </button>
                  <input 
                    v-model.number="stockInForm.quantity"
                    type="number" 
                    min="1"
                    class="flex-1 py-4 rounded-xl bg-gray-50 text-3xl text-center font-bold focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
                  />
                  <button 
                    @click="stockInForm.quantity++"
                    class="w-14 h-14 rounded-xl bg-green-100 flex items-center justify-center text-2xl font-bold text-green-600 hover:bg-green-200 transition-colors"
                  >
                    +
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">
                  <Calendar :size="18" class="inline mr-2" />
                  日期
                </label>
                <input 
                  v-model="stockInForm.recordDate"
                  type="date" 
                  class="w-full px-4 py-4 rounded-xl bg-gray-50 text-lg focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">来源</label>
                <div class="flex gap-2">
                  <button
                    v-for="source in sourceOptions"
                    :key="source"
                    @click="stockInForm.source = source"
                    :class="[
                      'flex-1 py-3 rounded-xl text-base font-medium transition-all',
                      stockInForm.source === source 
                        ? 'bg-green-100 text-green-600 ring-2 ring-green-200' 
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    {{ source }}
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">单价（可选）</label>
                <input 
                  v-model.number="stockInForm.price"
                  type="number" 
                  min="0"
                  step="0.01"
                  placeholder="输入单价" 
                  class="w-full px-4 py-4 rounded-xl bg-gray-50 text-lg focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">备注</label>
                <textarea 
                  v-model="stockInForm.remark"
                  placeholder="添加备注（可选）" 
                  class="w-full px-4 py-4 rounded-xl bg-gray-50 text-base min-h-[80px] resize-none focus:outline-none focus:ring-2 focus:ring-green-300 transition-all"
                  rows="2"
                ></textarea>
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showStockInDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleStockIn"
                class="flex-1 py-4 rounded-xl bg-gradient-to-r from-green-400 to-green-500 text-white text-lg font-medium hover:shadow-lg transition-all"
              >
                确认入库
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="fade">
        <div 
          v-if="showStockOutDialog" 
          class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" 
          @click.self="showStockOutDialog = false"
        >
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">
                出库 - {{ currentItem?.name }}
              </h3>
              <button 
                @click="showStockOutDialog = false"
                class="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center hover:bg-gray-200 transition-colors"
              >
                <X :size="20" class="text-gray-500" />
              </button>
            </div>

            <div class="space-y-5">
              <div class="text-center py-6 bg-red-50 rounded-2xl">
                <p class="text-gray-500 text-base mb-2">当前库存</p>
                <p 
                  :class="[
                    'text-4xl font-bold',
                    currentItem?.isLowStock ? 'text-red-500' : 'text-gray-800'
                  ]"
                >
                  {{ currentItem?.totalQuantity }}
                  <span class="text-xl font-normal text-gray-500">{{ currentItem?.unit }}</span>
                </p>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">出库数量</label>
                <div class="flex items-center gap-4">
                  <button 
                    @click="stockOutForm.quantity = Math.max(1, stockOutForm.quantity - 1)"
                    class="w-14 h-14 rounded-xl bg-gray-100 flex items-center justify-center text-2xl font-bold text-gray-600 hover:bg-gray-200 transition-colors"
                  >
                    -
                  </button>
                  <input 
                    v-model.number="stockOutForm.quantity"
                    type="number" 
                    min="1"
                    :max="currentItem?.totalQuantity"
                    class="flex-1 py-4 rounded-xl bg-gray-50 text-3xl text-center font-bold focus:outline-none focus:ring-2 focus:ring-red-300 transition-all"
                  />
                  <button 
                    @click="stockOutForm.quantity = Math.min(currentItem?.totalQuantity || 999, stockOutForm.quantity + 1)"
                    class="w-14 h-14 rounded-xl bg-red-100 flex items-center justify-center text-2xl font-bold text-red-600 hover:bg-red-200 transition-colors"
                  >
                    +
                  </button>
                </div>
                <p 
                  v-if="currentItem && stockOutForm.quantity > currentItem.totalQuantity" 
                  class="text-red-500 text-sm mt-2 text-center"
                >
                  出库数量不能超过当前库存
                </p>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">
                  <Calendar :size="18" class="inline mr-2" />
                  日期
                </label>
                <input 
                  v-model="stockOutForm.recordDate"
                  type="date" 
                  class="w-full px-4 py-4 rounded-xl bg-gray-50 text-lg focus:outline-none focus:ring-2 focus:ring-red-300 transition-all"
                />
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">用途</label>
                <div class="flex gap-2">
                  <button
                    v-for="purpose in purposeOptions"
                    :key="purpose"
                    @click="stockOutForm.purpose = purpose"
                    :class="[
                      'flex-1 py-3 rounded-xl text-base font-medium transition-all',
                      stockOutForm.purpose === purpose 
                        ? 'bg-red-100 text-red-600 ring-2 ring-red-200' 
                        : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                    ]"
                  >
                    {{ purpose }}
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-base font-medium text-gray-700 mb-3">备注</label>
                <textarea 
                  v-model="stockOutForm.remark"
                  placeholder="添加备注（可选）" 
                  class="w-full px-4 py-4 rounded-xl bg-gray-50 text-base min-h-[80px] resize-none focus:outline-none focus:ring-2 focus:ring-red-300 transition-all"
                  rows="2"
                ></textarea>
              </div>
            </div>

            <div class="flex gap-3 mt-8">
              <button 
                @click="showStockOutDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleStockOut"
                :disabled="!!currentItem && stockOutForm.quantity > currentItem.totalQuantity"
                :class="[
                  'flex-1 py-4 rounded-xl text-white text-lg font-medium transition-all',
                  currentItem && stockOutForm.quantity <= currentItem.totalQuantity
                    ? 'bg-gradient-to-r from-red-400 to-red-500 hover:shadow-lg'
                    : 'bg-gray-300 cursor-not-allowed'
                ]"
              >
                确认出库
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
