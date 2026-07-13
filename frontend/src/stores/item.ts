import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { HouseholdItem, ItemRecord, ItemStats, PageResult } from '@/types'
import { itemApi } from '@/api'
import { mockItems, generateMockItemRecords, mockItemStats, getMockLowStockItems } from '@/mock'
import { generateId, dayjs } from '@/utils'

const categoryMap: Record<string, string> = {
  diaper: '尿不湿',
  milk: '奶粉',
  food: '食品',
  daily: '日用品',
  medicine: '药品',
  other: '其他',
}

let mockItemsData = [...mockItems]
let mockRecordsData: Record<string, ItemRecord[]> = {}

mockItemsData.forEach(item => {
  mockRecordsData[item.id] = generateMockItemRecords(item.id)
})

export const useItemStore = defineStore('item', () => {
  const items = ref<HouseholdItem[]>([])
  const currentItem = ref<HouseholdItem | null>(null)
  const records = ref<ItemRecord[]>([])
  const loading = ref(false)
  const stats = ref<ItemStats>({
    totalItems: 0,
    lowStockItems: 0,
    categories: [],
    monthlyIn: 0,
    monthlyOut: 0,
  })
  const lowStockItems = ref<HouseholdItem[]>([])
  const recordsPage = ref(1)
  const recordsTotal = ref(0)
  const recordsPageSize = ref(10)

  const filteredItems = computed(() => items.value)

  async function fetchItems(category?: string, keyword?: string) {
    loading.value = true
    try {
      const data = await itemApi.getItemList(category)
      let result = data
      if (keyword) {
        result = result.filter(item => 
          item.name.toLowerCase().includes(keyword.toLowerCase())
        )
      }
      items.value = result
      return result
    } catch (e) {
      console.warn('使用mock物品数据:', e)
      let list = [...mockItemsData]
      if (category && category !== 'all') {
        list = list.filter(item => item.category === category)
      }
      if (keyword) {
        list = list.filter(item => 
          item.name.toLowerCase().includes(keyword.toLowerCase())
        )
      }
      items.value = list
      return list
    } finally {
      loading.value = false
    }
  }

  async function fetchItemDetail(id: string | number) {
    loading.value = true
    try {
      const data = await itemApi.getItemDetail(id)
      currentItem.value = data
      return data
    } catch (e) {
      console.warn('使用mock物品详情数据:', e)
      const found = mockItemsData.find(item => String(item.id) === String(id))
      currentItem.value = found || null
      return currentItem.value
    } finally {
      loading.value = false
    }
  }

  async function fetchStats() {
    try {
      const data = await itemApi.getStats()
      stats.value = data
      return data
    } catch (e) {
      console.warn('使用mock统计数据:', e)
      stats.value = {
        totalItems: mockItemsData.length,
        lowStockItems: mockItemsData.filter(i => i.isLowStock).length,
        categories: Object.entries(categoryMap).map(([key, name]) => ({
          category: key,
          categoryName: name,
          count: mockItemsData.filter(i => i.category === key).length,
        })),
        monthlyIn: 28,
        monthlyOut: 35,
      }
      return stats.value
    }
  }

  async function fetchLowStockItems() {
    try {
      const data = await itemApi.getLowStockItems()
      lowStockItems.value = data
      return data
    } catch (e) {
      console.warn('使用mock低库存数据:', e)
      lowStockItems.value = mockItemsData.filter(i => i.isLowStock)
      return lowStockItems.value
    }
  }

  async function createItem(data: Partial<HouseholdItem>) {
    loading.value = true
    try {
      const result = await itemApi.createItem(data)
      items.value.unshift(result)
      stats.value.totalItems++
      if (result.isLowStock) {
        stats.value.lowStockItems++
      }
      return result
    } catch (e) {
      console.warn('使用mock创建物品:', e)
      const newItem: HouseholdItem = {
        id: generateId(),
        name: data.name || '新物品',
        category: data.category || 'other',
        categoryName: categoryMap[data.category || 'other'] || '其他',
        unit: data.unit || '个',
        totalQuantity: data.totalQuantity || 0,
        warningQuantity: data.warningQuantity || 0,
        icon: data.icon || '📦',
        remark: data.remark || '',
        isLowStock: (data.totalQuantity || 0) <= (data.warningQuantity || 0),
        createTime: dayjs().format('YYYY-MM-DD HH:mm:ss'),
      }
      mockItemsData.unshift(newItem)
      items.value.unshift(newItem)
      stats.value.totalItems++
      if (newItem.isLowStock) {
        stats.value.lowStockItems++
      }
      mockRecordsData[newItem.id] = []
      return newItem
    } finally {
      loading.value = false
    }
  }

  async function updateItem(id: string | number, data: Partial<HouseholdItem>) {
    loading.value = true
    try {
      const result = await itemApi.updateItem(id, data)
      const index = items.value.findIndex(i => i.id === id)
      if (index !== -1) {
        const wasLowStock = items.value[index].isLowStock
        items.value[index] = result
        if (result.isLowStock !== wasLowStock) {
          stats.value.lowStockItems += result.isLowStock ? 1 : -1
        }
      }
      if (currentItem.value?.id === id) {
        currentItem.value = result
      }
      return result
    } catch (e) {
      console.warn('使用mock更新物品:', e)
      const index = mockItemsData.findIndex(i => String(i.id) === String(id))
      if (index !== -1) {
        const wasLowStock = mockItemsData[index].isLowStock
        mockItemsData[index] = { 
          ...mockItemsData[index], 
          ...data,
          isLowStock: (data.totalQuantity !== undefined ? data.totalQuantity : mockItemsData[index].totalQuantity) 
            <= (data.warningQuantity !== undefined ? data.warningQuantity : mockItemsData[index].warningQuantity),
          categoryName: data.category ? (categoryMap[data.category] || '其他') : mockItemsData[index].categoryName,
        }
        const storeIndex = items.value.findIndex(i => String(i.id) === String(id))
        if (storeIndex !== -1) {
          items.value[storeIndex] = mockItemsData[index]
        }
        if (currentItem.value?.id === id) {
          currentItem.value = mockItemsData[index]
        }
        if (mockItemsData[index].isLowStock !== wasLowStock) {
          stats.value.lowStockItems += mockItemsData[index].isLowStock ? 1 : -1
        }
        return mockItemsData[index]
      }
      return null
    } finally {
      loading.value = false
    }
  }

  async function deleteItem(id: string | number) {
    loading.value = true
    try {
      await itemApi.deleteItem(id)
      const item = items.value.find(i => i.id === id)
      items.value = items.value.filter(i => i.id !== id)
      if (item) {
        stats.value.totalItems--
        if (item.isLowStock) {
          stats.value.lowStockItems--
        }
      }
      if (currentItem.value?.id === id) {
        currentItem.value = null
      }
    } catch (e) {
      console.warn('使用mock删除物品:', e)
      const item = mockItemsData.find(i => String(i.id) === String(id))
      mockItemsData = mockItemsData.filter(i => String(i.id) !== String(id))
      items.value = items.value.filter(i => String(i.id) !== String(id))
      if (item) {
        stats.value.totalItems--
        if (item.isLowStock) {
          stats.value.lowStockItems--
        }
      }
      if (currentItem.value?.id === id) {
        currentItem.value = null
      }
      delete mockRecordsData[id]
    } finally {
      loading.value = false
    }
  }

  async function stockIn(id: string | number, data: any) {
    loading.value = true
    try {
      const result = await itemApi.stockIn(id, data)
      const index = items.value.findIndex(i => i.id === id)
      if (index !== -1) {
        const wasLowStock = items.value[index].isLowStock
        items.value[index] = result
        if (wasLowStock && !result.isLowStock) {
          stats.value.lowStockItems--
        }
      }
      if (currentItem.value?.id === id) {
        currentItem.value = result
      }
      return result
    } catch (e) {
      console.warn('使用mock入库:', e)
      const index = mockItemsData.findIndex(i => String(i.id) === String(id))
      if (index !== -1) {
        const wasLowStock = mockItemsData[index].isLowStock
        mockItemsData[index].totalQuantity += data.quantity || 0
        mockItemsData[index].isLowStock = mockItemsData[index].totalQuantity <= mockItemsData[index].warningQuantity
        
        const record: ItemRecord = {
          id: generateId(),
          itemId: id,
          itemName: mockItemsData[index].name,
          type: 'in',
          typeName: '入库',
          quantity: data.quantity || 0,
          unit: mockItemsData[index].unit,
          recordDate: data.recordDate || dayjs().format('YYYY-MM-DD'),
          operatorName: data.operatorName || '用户',
          source: data.source,
          price: data.price,
          totalPrice: data.price ? data.price * (data.quantity || 0) : undefined,
          remark: data.remark,
          createTime: dayjs().format('YYYY-MM-DD HH:mm:ss'),
        }
        if (!mockRecordsData[id]) {
          mockRecordsData[id] = []
        }
        mockRecordsData[id].unshift(record)
        
        const storeIndex = items.value.findIndex(i => String(i.id) === String(id))
        if (storeIndex !== -1) {
          items.value[storeIndex] = { ...mockItemsData[index] }
        }
        if (currentItem.value?.id === id) {
          currentItem.value = { ...mockItemsData[index] }
        }
        if (wasLowStock && !mockItemsData[index].isLowStock) {
          stats.value.lowStockItems--
        }
        return { ...mockItemsData[index] }
      }
      return null
    } finally {
      loading.value = false
    }
  }

  async function stockOut(id: string | number, data: any) {
    loading.value = true
    try {
      const result = await itemApi.stockOut(id, data)
      const index = items.value.findIndex(i => i.id === id)
      if (index !== -1) {
        const wasLowStock = items.value[index].isLowStock
        items.value[index] = result
        if (!wasLowStock && result.isLowStock) {
          stats.value.lowStockItems++
        }
      }
      if (currentItem.value?.id === id) {
        currentItem.value = result
      }
      return result
    } catch (e) {
      console.warn('使用mock出库:', e)
      const index = mockItemsData.findIndex(i => String(i.id) === String(id))
      if (index !== -1) {
        const wasLowStock = mockItemsData[index].isLowStock
        mockItemsData[index].totalQuantity = Math.max(0, mockItemsData[index].totalQuantity - (data.quantity || 0))
        mockItemsData[index].isLowStock = mockItemsData[index].totalQuantity <= mockItemsData[index].warningQuantity
        
        const record: ItemRecord = {
          id: generateId(),
          itemId: id,
          itemName: mockItemsData[index].name,
          type: 'out',
          typeName: '出库',
          quantity: data.quantity || 0,
          unit: mockItemsData[index].unit,
          recordDate: data.recordDate || dayjs().format('YYYY-MM-DD'),
          operatorName: data.operatorName || '用户',
          source: data.purpose,
          remark: data.remark,
          createTime: dayjs().format('YYYY-MM-DD HH:mm:ss'),
        }
        if (!mockRecordsData[id]) {
          mockRecordsData[id] = []
        }
        mockRecordsData[id].unshift(record)
        
        const storeIndex = items.value.findIndex(i => String(i.id) === String(id))
        if (storeIndex !== -1) {
          items.value[storeIndex] = { ...mockItemsData[index] }
        }
        if (currentItem.value?.id === id) {
          currentItem.value = { ...mockItemsData[index] }
        }
        if (!wasLowStock && mockItemsData[index].isLowStock) {
          stats.value.lowStockItems++
        }
        return { ...mockItemsData[index] }
      }
      return null
    } finally {
      loading.value = false
    }
  }

  async function fetchRecords(id: string | number, page: number = 1, size: number = 10, type?: string) {
    loading.value = true
    try {
      const data = await itemApi.getRecordList(id, page, size)
      let list = data.list
      if (type && type !== 'all') {
        list = list.filter(r => r.type === type)
      }
      records.value = list
      recordsPage.value = data.page
      recordsTotal.value = data.total
      recordsPageSize.value = data.pageSize
      return data
    } catch (e) {
      console.warn('使用mock记录数据:', e)
      let allRecords = mockRecordsData[id] || []
      if (type && type !== 'all') {
        allRecords = allRecords.filter(r => r.type === type)
      }
      const start = (page - 1) * size
      const end = start + size
      records.value = allRecords.slice(start, end)
      recordsPage.value = page
      recordsTotal.value = allRecords.length
      recordsPageSize.value = size
      return {
        list: records.value,
        total: allRecords.length,
        page,
        pageSize: size,
        totalPages: Math.ceil(allRecords.length / size),
      } as PageResult<ItemRecord>
    } finally {
      loading.value = false
    }
  }

  function clearCurrentItem() {
    currentItem.value = null
    records.value = []
  }

  return {
    items,
    currentItem,
    records,
    loading,
    stats,
    lowStockItems,
    recordsPage,
    recordsTotal,
    recordsPageSize,
    filteredItems,
    fetchItems,
    fetchItemDetail,
    fetchStats,
    fetchLowStockItems,
    createItem,
    updateItem,
    deleteItem,
    stockIn,
    stockOut,
    fetchRecords,
    clearCurrentItem,
  }
})
