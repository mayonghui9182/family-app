import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types'

declare module 'axios' {
  interface AxiosRequestConfig {
    silent?: boolean
  }
}

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'

const service: AxiosInstance = axios.create({
  baseURL,
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('token')
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`
    }
    if (config.silent === undefined && config.method?.toUpperCase() === 'GET') {
      config.silent = true
    }
    return config
  },
  (error) => {
    console.error('Request error:', error)
    return Promise.reject(error)
  }
)
service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data

    if (res.code !== 0 && res.code !== 200) {
      const silent = response.config?.silent
      if (!silent) {
        ElMessage.error(res.message || '请求失败')
      }

      if (res.code === 401 || res.code === 403) {
        localStorage.removeItem('token')
        localStorage.removeItem('userInfo')
        window.location.href = '/login'
      }

      return Promise.reject(new Error(res.message || '请求失败'))
    }

    return res.data
  },
  (error) => {
    console.error('Response error:', error)
    
    const silent = error.config?.silent

    if (error.response) {
      const { status } = error.response
      
      switch (status) {
        case 400:
          if (!silent) ElMessage.error('请求参数错误')
          break
        case 401:
          if (!silent) ElMessage.error('未授权，请重新登录')
          localStorage.removeItem('token')
          localStorage.removeItem('userInfo')
          window.location.href = '/login'
          break
        case 403:
          if (!silent) ElMessage.error('拒绝访问')
          break
        case 404:
          if (!silent) ElMessage.error('请求地址不存在')
          break
        case 500:
          if (!silent) ElMessage.error('服务器内部错误')
          break
        case 502:
          if (!silent) ElMessage.error('网关错误')
          break
        case 503:
          if (!silent) ElMessage.error('服务不可用')
          break
        case 504:
          if (!silent) ElMessage.error('网关超时')
          break
        default:
          if (!silent) ElMessage.error(error.response.data?.message || `请求失败 (${status})`)
      }
    } else if (error.message.includes('timeout')) {
      if (!silent) ElMessage.error('请求超时，请检查网络连接')
    } else if (error.message.includes('Network Error')) {
      if (!silent) ElMessage.error('网络连接失败，请检查网络')
    } else {
      if (!silent) ElMessage.error(error.message || '请求失败')
    }

    return Promise.reject(error)
  }
)

export function request<T = any>(config: AxiosRequestConfig): Promise<T> {
  return service.request<any, T>(config)
}

export function get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({
    url,
    method: 'GET',
    params,
    ...config,
  })
}

export function post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({
    url,
    method: 'POST',
    data,
    ...config,
  })
}

export function put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({
    url,
    method: 'PUT',
    data,
    ...config,
  })
}

export function del<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({
    url,
    method: 'DELETE',
    params,
    ...config,
  })
}

export function upload<T = any>(url: string, file: File, config?: AxiosRequestConfig): Promise<T> {
  const formData = new FormData()
  formData.append('file', file)

  return request<T>({
    url,
    method: 'POST',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data',
    },
    ...config,
  })
}

export default service
