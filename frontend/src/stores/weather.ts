import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Weather, ForecastDay, LifeIndex } from '@/types'
import { weatherApi } from '@/api'
import { mockWeather, mockForecast, mockLifeIndex } from '@/mock'

export const useWeatherStore = defineStore('weather', () => {
  const currentWeather = ref<Weather | null>(null)
  const forecast = ref<ForecastDay[]>([])
  const lifeIndex = ref<LifeIndex[]>([])
  const loading = ref(false)
  const currentCity = ref('北京市朝阳区')

  async function fetchCurrentWeather(city: string) {
    loading.value = true
    try {
      const data = await weatherApi.getCurrentWeather(city)
      currentWeather.value = data
      return data
    } catch (e) {
      console.warn('使用mock天气数据:', e)
      currentWeather.value = { ...mockWeather, city }
      return currentWeather.value
    } finally {
      loading.value = false
    }
  }

  async function fetchForecast(city: string, days: number = 7) {
    loading.value = true
    try {
      const data = await weatherApi.getForecast(city, days)
      forecast.value = data
      return data
    } catch (e) {
      console.warn('使用mock预报数据:', e)
      forecast.value = mockForecast.slice(0, days)
      return forecast.value
    } finally {
      loading.value = false
    }
  }

  async function fetchLifeIndex(city: string) {
    loading.value = true
    try {
      const data = await weatherApi.getLifeIndex(city)
      lifeIndex.value = data
      return data
    } catch (e) {
      console.warn('使用mock生活指数数据:', e)
      lifeIndex.value = mockLifeIndex
      return lifeIndex.value
    } finally {
      loading.value = false
    }
  }

  async function fetchAll(city: string) {
    currentCity.value = city
    loading.value = true
    try {
      const [weather, forecastData, lifeIndexData] = await Promise.all([
        fetchCurrentWeather(city),
        fetchForecast(city),
        fetchLifeIndex(city),
      ])
      return { weather, forecast: forecastData, lifeIndex: lifeIndexData }
    } finally {
      loading.value = false
    }
  }

  return {
    currentWeather,
    forecast,
    lifeIndex,
    loading,
    currentCity,
    fetchCurrentWeather,
    fetchForecast,
    fetchLifeIndex,
    fetchAll,
  }
})
