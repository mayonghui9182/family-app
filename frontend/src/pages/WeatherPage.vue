<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { Cloud, Sun, CloudRain, Wind, Droplets, ThermometerSun, MapPin, Eye, SunDim, CloudFog } from 'lucide-vue-next'
import { useWeatherStore } from '@/stores/weather'

const weatherStore = useWeatherStore()

const weatherIconMap: Record<string, any> = {
  sunny: Sun,
  cloudy: Cloud,
  rainy: CloudRain,
  overcast: CloudFog,
}

const weatherEmojiMap: Record<string, string> = {
  sunny: '☀️',
  cloudy: '⛅',
  rainy: '🌧️',
  overcast: '☁️',
}

const lifeIndexIcons: Record<string, string> = {
  shirt: '👕',
  sun: '☀️',
  dumbbell: '🏃',
  car: '🚗',
  thermometer: '🤒',
  wind: '💨',
}

onMounted(() => {
  weatherStore.fetchAll('北京市朝阳区')
})

const aqiColor = computed(() => {
  if (!weatherStore.currentWeather) return 'bg-gray-400'
  const aqi = weatherStore.currentWeather.aqi
  if (aqi <= 50) return 'bg-green-500'
  if (aqi <= 100) return 'bg-yellow-500'
  if (aqi <= 150) return 'bg-orange-500'
  if (aqi <= 200) return 'bg-red-500'
  return 'bg-purple-500'
})

const aqiLevel = computed(() => {
  if (!weatherStore.currentWeather) return ''
  const aqi = weatherStore.currentWeather.aqi
  if (aqi <= 50) return '优'
  if (aqi <= 100) return '良'
  if (aqi <= 150) return '轻度污染'
  if (aqi <= 200) return '中度污染'
  return '重度污染'
})
</script>

<template>
  <div class="page-container">
    <div class="safe-area-top">
      <div class="px-5 pt-4 pb-6 bg-gradient-to-b from-orange-100 to-transparent">
        <div class="flex items-center gap-2 text-gray-600 mb-6">
          <MapPin :size="18" class="text-primary-500" />
          <span class="font-medium">{{ weatherStore.currentCity }}</span>
        </div>

        <div v-if="weatherStore.currentWeather" class="text-center mb-6 animate-slide-up">
          <div class="text-7xl mb-4 animate-float">
            {{ weatherEmojiMap[weatherStore.currentWeather.icon] || '☀️' }}
          </div>
          <h1 class="text-6xl font-bold text-gray-800 mb-2">
            {{ weatherStore.currentWeather.temperature }}°
          </h1>
          <p class="text-gray-500 text-lg mb-1">{{ weatherStore.currentWeather.condition }}</p>
          <p class="text-gray-400 text-sm">
            体感 {{ weatherStore.currentWeather.feelsLike }}° · 
            {{ weatherStore.currentWeather.windDirection }} 
            {{ weatherStore.currentWeather.windSpeed }}级
          </p>
        </div>

        <div class="card-base mb-4 animate-slide-up">
          <div class="grid grid-cols-4 gap-4">
            <div class="text-center">
              <div class="flex items-center justify-center mb-2">
                <Droplets :size="20" class="text-blue-500" />
              </div>
              <p class="text-xl font-bold text-gray-800">{{ weatherStore.currentWeather?.humidity }}%</p>
              <p class="text-xs text-gray-500">湿度</p>
            </div>
            <div class="text-center">
              <div class="flex items-center justify-center mb-2">
                <Wind :size="20" class="text-teal-500" />
              </div>
              <p class="text-xl font-bold text-gray-800">{{ weatherStore.currentWeather?.windSpeed }}级</p>
              <p class="text-xs text-gray-500">风力</p>
            </div>
            <div class="text-center">
              <div class="flex items-center justify-center mb-2">
                <SunDim :size="20" class="text-yellow-500" />
              </div>
              <p class="text-xl font-bold text-gray-800">{{ weatherStore.currentWeather?.uvIndex }}</p>
              <p class="text-xs text-gray-500">紫外线</p>
            </div>
            <div class="text-center">
              <div class="flex items-center justify-center mb-2">
                <Eye :size="20" class="text-purple-500" />
              </div>
              <p class="text-xl font-bold text-gray-800">{{ weatherStore.currentWeather?.visibility }}km</p>
              <p class="text-xs text-gray-500">能见度</p>
            </div>
          </div>
        </div>

        <div class="card-base animate-slide-up">
          <div class="flex items-center justify-between mb-3">
            <p class="text-sm font-medium text-gray-700">空气质量</p>
            <p class="text-sm font-bold" :class="aqiColor.replace('bg-', 'text-')">
              {{ aqiLevel }} · {{ weatherStore.currentWeather?.aqi }}
            </p>
          </div>
          <div class="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
            <div 
              class="h-full rounded-full transition-all duration-500"
              :class="aqiColor"
              :style="{ width: `${Math.min(weatherStore.currentWeather?.aqi || 0 / 3, 100)}%` }"
            ></div>
          </div>
        </div>
      </div>
    </div>

    <div class="px-5">
      <h3 class="section-title">未来7天预报</h3>
      <div class="card-base mb-6">
        <div class="space-y-4">
          <div 
            v-for="(day, index) in weatherStore.forecast" 
            :key="day.date"
            class="flex items-center justify-between animate-slide-up"
            :style="{ animationDelay: `${index * 50}ms` }"
          >
            <span class="text-gray-600 font-medium w-16 flex-shrink-0">
              {{ day.dayOfWeek }}
            </span>
            <div class="flex items-center gap-2 flex-1 justify-center">
              <span class="text-2xl">{{ weatherEmojiMap[day.icon] || '☀️' }}</span>
              <span class="text-gray-500 text-sm w-12 text-right">
                {{ day.condition }}
              </span>
            </div>
            <div class="flex items-center gap-2 flex-1 justify-end">
              <span class="text-gray-800 font-medium w-8 text-right">{{ day.high }}°</span>
              <div class="w-20 h-1.5 bg-gray-100 rounded-full overflow-hidden flex-shrink-0">
                <div 
                  class="h-full bg-gradient-to-r from-blue-400 to-orange-400 rounded-full"
                  :style="{ 
                    width: `${((day.high - day.low) / 15) * 100}%`,
                    marginLeft: `${((day.low - 15) / 20) * 100}%`
                  }"
                ></div>
              </div>
              <span class="text-gray-400 w-8">{{ day.low }}°</span>
            </div>
          </div>
        </div>
      </div>

      <h3 class="section-title">生活指数</h3>
      <div class="grid grid-cols-2 gap-3 mb-6">
        <div 
          v-for="(item, index) in weatherStore.lifeIndex" 
          :key="item.name"
          class="card-base animate-slide-up"
          :style="{ animationDelay: `${index * 50}ms` }"
        >
          <div class="flex items-center gap-2 mb-2">
            <span class="text-2xl">{{ lifeIndexIcons[item.icon] || '📊' }}</span>
            <p class="text-sm text-gray-500">{{ item.name }}</p>
          </div>
          <p class="text-lg font-bold text-gray-800">{{ item.level }}</p>
          <p class="text-xs text-gray-400 mt-1 line-clamp-2">{{ item.description }}</p>
        </div>
      </div>
    </div>
  </div>
</template>
