<script setup lang="ts">
import { onMounted, ref, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { 
  ArrowLeft, 
  MoreVertical, 
  Plus, 
  Minus, 
  AlertTriangle,
  X,
  Calendar,
  Edit,
  Trash2,
  Package
} from 'lucide-vue-next'
import { useItemStore } from '@/stores/item'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dayjs } from '@/utils'
import type { HouseholdItem } from '@/types'

const route = useRoute()
const router = useRouter()
const itemStore = useItemStore()

const itemId = computed(() => String(route.params.id))
const item = computed(() => itemStore.currentItem)

const showMoreMenu = ref(false)
const showEditDialog = ref(false)
const showStockInDialog = ref(false)
const showStockOutDialog = ref(false)
const activeRecordTab = ref<'all' | 'in' | 'out'>('all')
const isLoadingMore = ref(false)

const categories = [
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

const recordTabs = [
  { key: 'all' as const, label: '全部' },
  { key: 'in' as const, label: '入库' },
  { key: 'out' as const, label: '出库' },
]

onMounted(async () => {
  await Promise.all([
    itemStore.fetchItemDetail(itemId.value),
    itemStore.fetchRecords(itemId.value, 1, 20),
  ])
})

onUnmounted(() => {
  itemStore.clearCurrentItem()
})

const filteredRecords = computed(() => {
  let records = itemStore.records
  if (activeRecordTab.value === 'in') {
    records = records.filter(r => r.type === 'in')
  } else if (activeRecordTab.value === 'out') {
    records = records.filter(r => r.type === 'out')
  }
  return records
})

const goBack = () => {
  router.back()
}

const openEditDialog = () => {
  if (!item.value) return
  showMoreMenu.value = false
  formData.value = {
    name: item.value.name,
    category: item.value.category,
    unit: item.value.unit,
    totalQuantity: item.value.totalQuantity,
    warningQuantity: item.value.warningQuantity,
    icon: item.value.icon || '📦',
    remark: item.value.remark || '',
  }
  showEditDialog.value = true
}

const handleDelete = async () => {
  if (!item.value) return
  showMoreMenu.value = false
  try {
    await ElMessageBox.confirm('确定要删除这个物品吗？删除后无法恢复。', '删除物品', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await itemStore.deleteItem(item.value.id)
    ElMessage.success('删除成功')
    router.back()
  } catch {
  }
}

const handleUpdateItem = async () => {
  if (!item.value) return
  if (!formData.value.name.trim()) {
    ElMessage.warning('请输入物品名称')
    return
  }
  try {
    await itemStore.updateItem(item.value.id, formData.value)
    ElMessage.success('修改成功')
    showEditDialog.value = false
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const openStockInDialog = () => {
  if (!item.value) return
  stockInForm.value = {
    quantity: 1,
    recordDate: dayjs().format('YYYY-MM-DD'),
    source: '购买',
    price: undefined,
    remark: '',
  }
  showStockInDialog.value = true
}

const openStockOutDialog = () => {
  if (!item.value) return
  stockOutForm.value = {
    quantity: 1,
    recordDate: dayjs().format('YYYY-MM-DD'),
    purpose: '使用',
    remark: '',
  }
  showStockOutDialog.value = true
}

const handleStockIn = async () => {
  if (!item.value) return
  if (!stockInForm.value.quantity || stockInForm.value.quantity <= 0) {
    ElMessage.warning('请输入正确的入库数量')
    return
  }
  try {
    await itemStore.stockIn(item.value.id, stockInForm.value)
    await itemStore.fetchRecords(item.value.id, 1, 20)
    ElMessage.success('入库成功')
    showStockInDialog.value = false
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const handleStockOut = async () => {
  if (!item.value) return
  if (!stockOutForm.value.quantity || stockOutForm.value.quantity <= 0) {
    ElMessage.warning('请输入正确的出库数量')
    return
  }
  if (stockOutForm.value.quantity > item.value.totalQuantity) {
    ElMessage.warning('出库数量不能超过当前库存')
    return
  }
  try {
    await itemStore.stockOut(item.value.id, stockOutForm.value)
    await itemStore.fetchRecords(item.value.id, 1, 20)
    ElMessage.success('出库成功')
    showStockOutDialog.value = false
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const loadMoreRecords = async () => {
  if (isLoadingMore.value) return
  if (itemStore.recordsPage * itemStore.recordsPageSize >= itemStore.recordsTotal) return
  
  isLoadingMore.value = true
  try {
    await itemStore.fetchRecords(
      itemId.value, 
      itemStore.recordsPage + 1, 
      itemStore.recordsPageSize,
      activeRecordTab.value
    )
  } finally {
    isLoadingMore.value = false
  }
}

const formatDate = (dateStr: string) => {
  const d = dayjs(dateStr)
  if (d.isSame(dayjs(), 'day')) return '今天'
  if (d.isSame(dayjs().subtract(1, 'day'), 'day')) return '昨天'
  return d.format('M月D日')
}
</script>

<template>
  <div class="page-container pb-8" v-if="item">
    <div class="safe-area-top bg-gradient-to-b from-green-50 to-transparent">
      <div class="px-5 pt-4">
        <div class="flex items-center justify-between mb-6">
          <div class="flex items-center gap-4">
            <button 
              @click="goBack"
              class="w-10 h-10 rounded-full bg-white shadow-md flex items-center justify-center hover:bg-gray-50 transition-colors"
            >
              <ArrowLeft :size="22" class="text-gray-600" />
            </button>
            <h1 class="text-2xl font-bold text-gray-800">{{ item.name }}</h1>
          </div>
          <div class="relative">
            <button 
              @click="showMoreMenu = !showMoreMenu"
              class="w-10 h-10 rounded-full bg-white shadow-md flex items-center justify-center hover:bg-gray-50 transition-colors"
            >
              <MoreVertical :size="22" class="text-gray-600" />
            </button>
            <Transition name="fade">
              <div 
                v-if="showMoreMenu" 
                class="absolute right-0 top-12 bg-white rounded-2xl shadow-lg py-2 min-w-[140px] z-20 overflow-hidden"
              >
                <button 
                  @click="openEditDialog"
                  class="w-full px-5 py-3 text-left text-base text-gray-700 hover:bg-gray-50 transition-colors flex items-center gap-3"
                >
                  <Edit :size="18" />
                  <span>编辑</span>
                </button>
                <button 
                  @click="handleDelete"
                  class="w-full px-5 py-3 text-left text-base text-red-500 hover:bg-red-50 transition-colors flex items-center gap-3"
                >
                  <Trash2 :size="18" />
                  <span>删除</span>
                </button>
              </div>
            </Transition>
          </div>
        </div>
      </div>
    </div>

    <div class="px-5">
      <div 
        :class="[
          'rounded-3xl p-6 mb-6 text-center',
          item.isLowStock 
            ? 'bg-gradient-to-br from-red-50 to-orange-50' 
            : 'bg-gradient-to-br from-green-50 to-teal-50'
        ]"
      >
        <div class="text-6xl mb-4">{{ item.icon }}</div>
        <p class="text-gray-500 text-base mb-2">当前库存</p>
        <p 
          :class="[
            'text-6xl font-bold mb-4',
            item.isLowStock ? 'text-red-500' : 'text-gray-800'
          ]"
        >
          {{ item.totalQuantity }}
          <span class="text-2xl font-normal text-gray-500 ml-1">{{ item.unit }}</span>
        </p>
        <div 
          v-if="item.isLowStock" 
          class="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-red-100 text-red-600"
        >
          <AlertTriangle :size="18" />
          <span class="font-medium">库存不足，请及时补充</span>
        </div>
      </div>

      <div class="bg-white rounded-2xl shadow-md p-5 mb-6">
        <div class="grid grid-cols-2 gap-4">
          <div class="flex items-center gap-3">
            <div class="w-12 h-12 rounded-xl bg-blue-50 flex items-center justify-center">
              <Package :size="22" class="text-blue-500" />
            </div>
            <div>
              <p class="text-sm text-gray-400">分类</p>
              <p class="text-lg font-semibold text-gray-700">{{ item.categoryName }}</p>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <div class="w-12 h-12 rounded-xl bg-orange-50 flex items-center justify-center">
              <AlertTriangle :size="22" class="text-orange-500" />
            </div>
            <div>
              <p class="text-sm text-gray-400">预警数量</p>
              <p class="text-lg font-semibold text-gray-700">{{ item.warningQuantity }}{{ item.unit }}</p>
            </div>
          </div>
        </div>
        <div v-if="item.remark" class="mt-4 pt-4 border-t border-gray-100">
          <p class="text-sm text-gray-400 mb-1">备注</p>
          <p class="text-base text-gray-700">{{ item.remark }}</p>
        </div>
      </div>

      <div class="grid grid-cols-2 gap-4 mb-6">
        <button 
          @click="openStockOutDialog"
          class="py-5 rounded-2xl bg-gradient-to-r from-red-400 to-red-500 text-white text-xl font-bold shadow-lg hover:shadow-xl transition-all active:scale-98 flex items-center justify-center gap-2"
        >
          <Minus :size="26" />
          <span>使用</span>
        </button>
        <button 
          @click="openStockInDialog"
          class="py-5 rounded-2xl bg-gradient-to-r from-green-400 to-green-500 text-white text-xl font-bold shadow-lg hover:shadow-xl transition-all active:scale-98 flex items-center justify-center gap-2"
        >
          <Plus :size="26" />
          <span>录入</span>
        </button>
      </div>

      <div class="bg-white rounded-2xl shadow-md overflow-hidden">
        <div class="flex border-b border-gray-100">
          <button
            v-for="tab in recordTabs"
            :key="tab.key"
            @click="activeRecordTab = tab.key"
            :class="[
              'flex-1 py-4 text-base font-medium transition-all relative',
              activeRecordTab === tab.key
                ? 'text-green-600'
                : 'text-gray-500 hover:text-gray-700'
            ]"
          >
            {{ tab.label }}
            <div 
              v-if="activeRecordTab === tab.key"
              class="absolute bottom-0 left-1/2 -translate-x-1/2 w-8 h-1 bg-green-500 rounded-full"
            ></div>
          </button>
        </div>

        <div class="divide-y divide-gray-50">
          <div 
            v-for="(record, index) in filteredRecords" 
            :key="record.id"
            class="p-4 hover:bg-gray-50 transition-colors"
          >
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-3">
                <div 
                  :class="[
                    'w-12 h-12 rounded-xl flex items-center justify-center',
                    record.type === 'in' ? 'bg-green-50' : 'bg-red-50'
                  ]"
                >
                  <Plus 
                    v-if="record.type === 'in'" 
                    :size="22" 
                    class="text-green-500" 
                  />
                  <Minus 
                    v-else 
                    :size="22" 
                    class="text-red-500" 
                  />
                </div>
                <div>
                  <p class="text-base font-medium text-gray-800">
                    {{ record.typeName }}
                  </p>
                  <p class="text-sm text-gray-400 flex items-center gap-2">
                    <Calendar :size="12" />
                    {{ formatDate(record.recordDate) }}
                    <span v-if="record.operatorName">· {{ record.operatorName }}</span>
                  </p>
                </div>
              </div>
              <div class="text-right">
                <p 
                  :class="[
                    'text-xl font-bold',
                    record.type === 'in' ? 'text-green-500' : 'text-red-500'
                  ]"
                >
                  {{ record.type === 'in' ? '+' : '-' }}{{ record.quantity }}
                  <span class="text-sm font-normal">{{ record.unit }}</span>
                </p>
                <p v-if="record.source" class="text-sm text-gray-400">
                  {{ record.source }}
                </p>
              </div>
            </div>
            <div v-if="record.remark" class="mt-2 pl-15">
              <p class="text-sm text-gray-400">备注: {{ record.remark }}</p>
            </div>
          </div>
        </div>

        <div v-if="filteredRecords.length === 0" class="text-center py-12">
          <div class="text-5xl mb-3">📋</div>
          <p class="text-gray-400 text-base">暂无记录</p>
        </div>

        <div 
          v-if="itemStore.recordsPage * itemStore.recordsPageSize < itemStore.recordsTotal"
          class="p-4 text-center"
        >
          <button 
            @click="loadMoreRecords"
            :disabled="isLoadingMore"
            class="text-green-500 text-base font-medium"
          >
            {{ isLoadingMore ? '加载中...' : '加载更多' }}
          </button>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div 
          v-if="showEditDialog" 
          class="fixed inset-0 bg-black/50 z-50 flex items-end sm:items-center justify-center" 
          @click.self="showEditDialog = false"
        >
          <div class="bg-white w-full sm:max-w-lg sm:rounded-3xl rounded-t-3xl p-6 animate-slide-up max-h-[90vh] overflow-y-auto">
            <div class="flex items-center justify-between mb-6">
              <h3 class="text-xl font-bold text-gray-800">编辑物品</h3>
              <button 
                @click="showEditDialog = false"
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
                    v-for="cat in categories"
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
                  <label class="block text-base font-medium text-gray-700 mb-3">当前库存</label>
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
                @click="showEditDialog = false"
                class="flex-1 py-4 rounded-xl border-2 border-gray-200 text-gray-600 text-lg font-medium hover:bg-gray-50 transition-colors"
              >
                取消
              </button>
              <button 
                @click="handleUpdateItem"
                class="flex-1 py-4 rounded-xl bg-gradient-to-r from-green-400 to-green-500 text-white text-lg font-medium hover:shadow-lg transition-all"
              >
                保存
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
              <h3 class="text-xl font-bold text-gray-800">入库 - {{ item?.name }}</h3>
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
                  {{ item?.totalQuantity }}
                  <span class="text-xl font-normal text-gray-500">{{ item?.unit }}</span>
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
              <h3 class="text-xl font-bold text-gray-800">出库 - {{ item?.name }}</h3>
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
                    item?.isLowStock ? 'text-red-500' : 'text-gray-800'
                  ]"
                >
                  {{ item?.totalQuantity }}
                  <span class="text-xl font-normal text-gray-500">{{ item?.unit }}</span>
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
                    :max="item?.totalQuantity"
                    class="flex-1 py-4 rounded-xl bg-gray-50 text-3xl text-center font-bold focus:outline-none focus:ring-2 focus:ring-red-300 transition-all"
                  />
                  <button 
                    @click="stockOutForm.quantity = Math.min(item?.totalQuantity || 999, stockOutForm.quantity + 1)"
                    class="w-14 h-14 rounded-xl bg-red-100 flex items-center justify-center text-2xl font-bold text-red-600 hover:bg-red-200 transition-colors"
                  >
                    +
                  </button>
                </div>
                <p 
                  v-if="item && stockOutForm.quantity > item.totalQuantity" 
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
                :disabled="!!item && stockOutForm.quantity > item.totalQuantity"
                :class="[
                  'flex-1 py-4 rounded-xl text-white text-lg font-medium transition-all',
                  item && stockOutForm.quantity <= item.totalQuantity
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

<style scoped>
.pl-15 {
  padding-left: 3.75rem;
}
</style>
