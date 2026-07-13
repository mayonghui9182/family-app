<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, MapPin, Star, Heart, Share2, Calendar, Clock, Users, ChevronDown, ChevronUp } from 'lucide-vue-next'
import { useTravelStore } from '@/stores/travel'

const route = useRoute()
const router = useRouter()
const travelStore = useTravelStore()

const isLiked = ref(false)
const showAllTips = ref(false)

const travelId = computed(() => {
  const id = route.params.id
  return Array.isArray(id) ? id[0] : id
})

onMounted(() => {
  const id = travelId.value
  if (id) {
    travelStore.fetchTravelDetail(id)
  }
})

const travel = computed(() => travelStore.currentTravel)

const toggleLike = () => {
  isLiked.value = !isLiked.value
}

const transportIcons: Record<string, string> = {
  plane: '✈️',
  train: '🚄',
  bus: '🚌',
  ferry: '⛴️',
  ship: '⛴️',
  car: '🚗',
}
</script>

<template>
  <div class="page-container pb-24">
    <div v-if="travel" class="relative">
      <div class="relative h-72 bg-gradient-to-b from-green-200 to-green-50 overflow-hidden">
        <img 
          :src="travel.image" 
          :alt="travel.name"
          class="w-full h-full object-cover"
        />
        <div class="absolute inset-0 bg-gradient-to-t from-black/30 to-transparent"></div>
      </div>
      
      <div class="absolute top-0 left-0 right-0 flex items-center justify-between p-4 safe-area-top z-10">
        <button 
          @click="router.back()"
          class="w-10 h-10 rounded-full bg-white/90 backdrop-blur-sm flex items-center justify-center shadow-soft"
        >
          <ArrowLeft :size="20" class="text-gray-700" />
        </button>
        <div class="flex gap-2">
          <button 
            @click="toggleLike"
            class="w-10 h-10 rounded-full bg-white/90 backdrop-blur-sm flex items-center justify-center shadow-soft transition-all"
          >
            <Heart :size="20" :class="isLiked ? 'text-red-500 fill-red-500' : 'text-gray-700'" />
          </button>
          <button class="w-10 h-10 rounded-full bg-white/90 backdrop-blur-sm flex items-center justify-center shadow-soft">
            <Share2 :size="20" class="text-gray-700" />
          </button>
        </div>
      </div>
    </div>

    <div class="px-5 -mt-8 relative z-10">
      <div v-if="travel" class="card-base animate-slide-up">
        <div class="flex items-start justify-between mb-3">
          <div>
            <h1 class="text-2xl font-bold text-gray-800 mb-2">{{ travel.name }}</h1>
            <div class="flex items-center gap-1 text-gray-500 text-sm">
              <MapPin :size="16" />
              <span>{{ travel.location }}</span>
            </div>
          </div>
          <div class="flex items-center gap-1 bg-yellow-50 px-3 py-1.5 rounded-full">
            <Star :size="16" class="text-yellow-400 fill-yellow-400" />
            <span class="font-bold text-yellow-600">{{ travel.rating }}</span>
          </div>
        </div>

        <div class="flex gap-2 mb-4 flex-wrap">
          <span 
            v-for="tag in travel.tags" 
            :key="tag"
            class="badge-primary"
          >
            {{ tag }}
          </span>
        </div>

        <div class="grid grid-cols-3 gap-4 py-4 border-t border-b border-gray-100">
          <div class="text-center">
            <Calendar :size="20" class="text-primary-500 mx-auto mb-1" />
            <p class="text-sm font-medium text-gray-700">建议游玩</p>
            <p class="text-xs text-gray-500">{{ travel.suggestedDuration }}</p>
          </div>
          <div class="text-center">
            <Clock :size="20" class="text-primary-500 mx-auto mb-1" />
            <p class="text-sm font-medium text-gray-700">开放时间</p>
            <p class="text-xs text-gray-500">{{ travel.openTime }}</p>
          </div>
          <div class="text-center">
            <Users :size="20" class="text-primary-500 mx-auto mb-1" />
            <p class="text-sm font-medium text-gray-700">适合人群</p>
            <p class="text-xs text-gray-500">{{ travel.suitableFor.join('/') }}</p>
          </div>
        </div>
      </div>

      <div v-if="travel" class="mt-6">
        <h3 class="section-title">景点亮点</h3>
        <div class="card-base animate-slide-up">
          <div class="flex flex-wrap gap-2">
            <span 
              v-for="highlight in travel.highlights" 
              :key="highlight"
              class="inline-flex items-center gap-1 px-3 py-1.5 bg-gradient-to-r from-orange-50 to-yellow-50 text-orange-600 rounded-full text-sm font-medium"
            >
              ✨ {{ highlight }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="travel" class="mt-6">
        <h3 class="section-title">景点介绍</h3>
        <div class="card-base animate-slide-up">
          <p class="text-gray-600 leading-relaxed">
            {{ travel.description }}
          </p>
        </div>
      </div>

      <div v-if="travel" class="mt-6">
        <h3 class="section-title">交通指南</h3>
        <div class="card-base animate-slide-up">
          <div class="space-y-4">
            <div 
              v-for="transport in travel.transportation" 
              :key="transport.type"
              class="flex gap-3"
            >
              <div class="w-10 h-10 rounded-full bg-primary-50 flex items-center justify-center flex-shrink-0 text-xl">
                {{ transportIcons[transport.type] || '🚗' }}
              </div>
              <div>
                <p class="font-medium text-gray-800">{{ transport.name }}</p>
                <p class="text-sm text-gray-500">{{ transport.description }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="travel" class="mt-6">
        <h3 class="section-title">
          游玩贴士
          <span class="text-sm font-normal text-gray-400">({{ travel.tips.length }}条)</span>
        </h3>
        <div class="card-base animate-slide-up">
          <div class="space-y-3">
            <div 
              v-for="(tip, index) in (showAllTips ? travel.tips : travel.tips.slice(0, 3))" 
              :key="index"
              class="flex gap-3"
            >
              <div class="w-6 h-6 rounded-full bg-yellow-100 flex items-center justify-center flex-shrink-0 text-xs font-bold text-yellow-600">
                {{ index + 1 }}
              </div>
              <p class="text-gray-600 text-sm leading-relaxed">{{ tip }}</p>
            </div>
          </div>
          <button 
            v-if="travel.tips.length > 3"
            @click="showAllTips = !showAllTips"
            class="w-full mt-4 py-2 text-primary-500 text-sm font-medium flex items-center justify-center gap-1"
          >
            {{ showAllTips ? '收起' : '查看更多' }}
            <component :is="showAllTips ? ChevronUp : ChevronDown" :size="16" />
          </button>
        </div>
      </div>
    </div>

    <div v-if="travelStore.loading" class="px-5 pt-20">
      <div class="text-center">
        <div class="inline-block w-10 h-10 border-4 border-primary-200 border-t-primary-500 rounded-full animate-spin mb-4"></div>
        <p class="text-gray-400">加载中...</p>
      </div>
    </div>

    <div v-if="travel" class="fixed bottom-0 left-0 right-0 p-4 bg-white border-t border-gray-100 safe-area-bottom z-50">
      <div class="flex items-center justify-between">
        <div>
          <p class="text-gray-500 text-sm">起价</p>
          <p class="text-2xl font-bold text-primary-500">{{ travel.price }}</p>
        </div>
        <button class="btn-primary px-8">
          立即预订
        </button>
      </div>
    </div>
  </div>
</template>
