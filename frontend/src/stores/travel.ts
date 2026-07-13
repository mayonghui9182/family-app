import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { TravelGuide, PageResult } from '@/types'
import { travelApi } from '@/api'
import { mockTravelList } from '@/mock'

export const useTravelStore = defineStore('travel', () => {
  const travelList = ref<TravelGuide[]>([])
  const currentTravel = ref<TravelGuide | null>(null)
  const hotDestinations = ref<TravelGuide[]>([])
  const recommendations = ref<TravelGuide[]>([])
  const categories = ref<{ id: string; name: string; icon: string }[]>([])
  const loading = ref(false)
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(10)

  const totalPages = computed(() => Math.ceil(total.value / pageSize.value))

  async function fetchTravelList(params?: {
    page?: number
    pageSize?: number
    category?: string
    keyword?: string
  }) {
    loading.value = true
    try {
      const data = await travelApi.getTravelList(params)
      travelList.value = data.list
      total.value = data.total
      page.value = data.page
      pageSize.value = data.pageSize
      return data
    } catch (e) {
      console.warn('使用mock旅游数据:', e)
      let list = [...mockTravelList]
      if (params?.keyword) {
        list = list.filter(item => 
          item.name.includes(params.keyword!) || 
          item.location.includes(params.keyword!) ||
          item.tags.some(tag => tag.includes(params.keyword!))
        )
      }
      if (params?.category && params.category !== 'all') {
        list = list.filter(item => item.category === params.category)
      }
      const pageNum = params?.page || 1
      const size = params?.pageSize || 10
      const start = (pageNum - 1) * size
      const end = start + size
      travelList.value = list.slice(start, end)
      total.value = list.length
      page.value = pageNum
      pageSize.value = size
      return {
        list: travelList.value,
        total: list.length,
        page: pageNum,
        pageSize: size,
        totalPages: Math.ceil(list.length / size),
      } as PageResult<TravelGuide>
    } finally {
      loading.value = false
    }
  }

  async function fetchTravelDetail(id: string | number) {
    loading.value = true
    try {
      const data = await travelApi.getTravelDetail(id)
      currentTravel.value = data
      return data
    } catch (e) {
      console.warn('使用mock旅游详情数据:', e)
      const found = mockTravelList.find(item => String(item.id) === String(id))
      currentTravel.value = found || mockTravelList[0]
      return currentTravel.value
    } finally {
      loading.value = false
    }
  }

  async function fetchHotDestinations() {
    loading.value = true
    try {
      const data = await travelApi.getHotDestinations()
      hotDestinations.value = data
      return data
    } catch (e) {
      console.warn('使用mock热门目的地数据:', e)
      hotDestinations.value = mockTravelList.slice(0, 4)
      return hotDestinations.value
    } finally {
      loading.value = false
    }
  }

  async function fetchRecommendations() {
    loading.value = true
    try {
      const data = await travelApi.getRecommendations()
      recommendations.value = data
      return data
    } catch (e) {
      console.warn('使用mock推荐数据:', e)
      recommendations.value = mockTravelList.slice(0, 3)
      return recommendations.value
    } finally {
      loading.value = false
    }
  }

  async function fetchCategories() {
    try {
      const data = await travelApi.getCategories()
      categories.value = data
      return data
    } catch (e) {
      console.warn('使用mock分类数据:', e)
      categories.value = [
        { id: 'all', name: '全部', icon: 'grid' },
        { id: 'nature', name: '自然风光', icon: 'mountain' },
        { id: 'beach', name: '海滩度假', icon: 'umbrella' },
        { id: 'cultural', name: '历史文化', icon: 'landmark' },
      ]
      return categories.value
    }
  }

  function clearCurrentTravel() {
    currentTravel.value = null
  }

  return {
    travelList,
    currentTravel,
    hotDestinations,
    recommendations,
    categories,
    loading,
    total,
    page,
    pageSize,
    totalPages,
    fetchTravelList,
    fetchTravelDetail,
    fetchHotDestinations,
    fetchRecommendations,
    fetchCategories,
    clearCurrentTravel,
  }
})
