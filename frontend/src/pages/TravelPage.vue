<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { MapPin, Star, ArrowRight, Search, Filter } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useTravelStore } from '@/stores/travel'
import { debounce } from '@/utils'

const router = useRouter()
const travelStore = useTravelStore()

const searchKeyword = ref('')
const activeCategory = ref('all')

const categories = [
  { id: 'all', name: '热门', icon: '🔥' },
  { id: 'nature', name: '自然风光', icon: '🏔️' },
  { id: 'beach', name: '海滩度假', icon: '🏖️' },
  { id: 'cultural', name: '历史文化', icon: '🏛️' },
]

onMounted(() => {
  travelStore.fetchTravelList({ page: 1, pageSize: 20 })
  travelStore.fetchCategories()
})

const handleSearch = debounce((keyword: string) => {
  travelStore.fetchTravelList({ 
    page: 1, 
    pageSize: 20, 
    keyword,
    category: activeCategory.value !== 'all' ? activeCategory.value : undefined
  })
}, 300)

const handleCategoryClick = (categoryId: string) => {
  activeCategory.value = categoryId
  travelStore.fetchTravelList({
    page: 1,
    pageSize: 20,
    keyword: searchKeyword.value || undefined,
    category: categoryId !== 'all' ? categoryId : undefined
  })
}

const travelList = computed(() => travelStore.travelList)
</script>

<template>
  <div class="page-container">
    <div class="safe-area-top bg-gradient-to-b from-green-100 to-transparent pb-4">
      <div class="px-5 pt-4">
        <h1 class="text-2xl font-bold text-gray-800 mb-4">发现美景</h1>

        <div class="relative mb-6">
          <Search :size="20" class="absolute left-4 top-1/2 -translate-y-1/2 text-gray-400" />
          <input 
            v-model="searchKeyword"
            type="text" 
            placeholder="搜索目的地、景点..." 
            class="input-base pl-12"
            @input="handleSearch(searchKeyword)"
          />
        </div>

        <div class="flex gap-2 overflow-x-auto scrollbar-hide pb-2 mb-2">
          <button 
            v-for="category in categories" 
            :key="category.id"
            @click="handleCategoryClick(category.id)"
            :class="[
              'px-4 py-2 rounded-full text-sm font-medium whitespace-nowrap transition-all flex items-center gap-1.5',
              activeCategory === category.id 
                ? 'bg-gradient-secondary text-white shadow-soft' 
                : 'bg-white text-gray-600 hover:bg-gray-50'
            ]"
          >
            <span>{{ category.icon }}</span>
            {{ category.name }}
          </button>
        </div>
      </div>
    </div>

    <div class="px-5">
      <div class="flex items-center justify-between mb-4">
        <h3 class="section-title mb-0">
          {{ activeCategory === 'all' ? '热门目的地' : categories.find(c => c.id === activeCategory)?.name }}
        </h3>
        <span class="text-sm text-gray-400">共 {{ travelStore.total }} 个</span>
      </div>

      <div v-if="travelStore.loading" class="space-y-4">
        <div v-for="i in 3" :key="i" class="card-base animate-pulse">
          <div class="flex gap-4">
            <div class="w-24 h-24 rounded-xl bg-gray-200 flex-shrink-0"></div>
            <div class="flex-1 space-y-2">
              <div class="h-5 bg-gray-200 rounded w-3/4"></div>
              <div class="h-4 bg-gray-200 rounded w-1/2"></div>
              <div class="h-4 bg-gray-200 rounded w-1/4"></div>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="space-y-4 pb-6">
        <div 
          v-for="(dest, index) in travelList" 
          :key="dest.id"
          @click="router.push(`/travel/${dest.id}`)"
          class="card-base cursor-pointer animate-slide-up"
          :style="{ animationDelay: `${index * 50}ms` }"
        >
          <div class="flex gap-4">
            <div class="w-24 h-24 rounded-xl overflow-hidden flex-shrink-0 bg-gradient-card">
              <img 
                :src="dest.image" 
                :alt="dest.name"
                class="w-full h-full object-cover"
                loading="lazy"
              />
            </div>
            <div class="flex-1 min-w-0">
              <h4 class="text-lg font-bold text-gray-800 mb-1 truncate">{{ dest.name }}</h4>
              <div class="flex items-center gap-1 text-gray-500 text-sm mb-2">
                <MapPin :size="14" />
                <span class="truncate">{{ dest.location }}</span>
              </div>
              <div class="flex items-center gap-1 mb-2">
                <Star :size="14" class="text-yellow-400 fill-yellow-400" />
                <span class="text-sm font-medium text-gray-700">{{ dest.rating }}</span>
                <span class="text-xs text-gray-400">({{ dest.reviewCount }})</span>
              </div>
              <div class="flex gap-1.5 flex-wrap">
                <span 
                  v-for="tag in dest.tags.slice(0, 2)" 
                  :key="tag"
                  class="text-xs bg-primary-50 text-primary-500 px-2 py-0.5 rounded-full"
                >
                  {{ tag }}
                </span>
              </div>
            </div>
            <div class="text-right flex flex-col justify-between">
              <p class="text-primary-500 font-bold text-lg">{{ dest.price }}</p>
              <ArrowRight :size="20" class="text-gray-300 self-end" />
            </div>
          </div>
        </div>
      </div>

      <div v-if="!travelStore.loading && travelList.length === 0" class="text-center py-12">
        <div class="text-6xl mb-4">🔍</div>
        <p class="text-gray-500">没有找到相关目的地</p>
        <button @click="handleCategoryClick('all'); searchKeyword = ''" class="btn-ghost mt-4">
          查看全部
        </button>
      </div>
    </div>
  </div>
</template>
