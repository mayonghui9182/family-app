import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Todo, TodoPriority, PageResult } from '@/types'
import { todoApi } from '@/api'
import { mockTodos } from '@/mock'
import { generateId, dayjs } from '@/utils'

export const useTodoStore = defineStore('todo', () => {
  const todos = ref<Todo[]>([])
  const todayTodos = ref<Todo[]>([])
  const currentTodo = ref<Todo | null>(null)
  const loading = ref(false)
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(10)
  const stats = ref({
    total: 0,
    completed: 0,
    active: 0,
    today: 0,
  })

  const completedTodos = computed(() => todos.value.filter(t => t.completed))
  const activeTodos = computed(() => todos.value.filter(t => !t.completed))
  const highPriorityTodos = computed(() => todos.value.filter(t => t.priority === 'high' && !t.completed))

  async function fetchTodos(params?: {
    page?: number
    pageSize?: number
    completed?: boolean
    priority?: string
    category?: string
  }) {
    loading.value = true
    try {
      const data = await todoApi.getTodoList(params)
      todos.value = data.list
      total.value = data.total
      page.value = data.page
      pageSize.value = data.pageSize
      return data
    } catch (e) {
      console.warn('使用mock待办数据:', e)
      let list = [...mockTodos]
      if (params?.completed !== undefined) {
        list = list.filter(item => item.completed === params.completed)
      }
      if (params?.priority && params.priority !== 'all') {
        list = list.filter(item => item.priority === params.priority)
      }
      if (params?.category && params.category !== 'all') {
        list = list.filter(item => item.category === params.category)
      }
      const pageNum = params?.page || 1
      const size = params?.pageSize || 10
      const start = (pageNum - 1) * size
      const end = start + size
      todos.value = list.slice(start, end)
      total.value = list.length
      page.value = pageNum
      pageSize.value = size
      stats.value = {
        total: list.length,
        completed: list.filter(t => t.completed).length,
        active: list.filter(t => !t.completed).length,
        today: list.filter(t => t.dueDate === dayjs().format('YYYY-MM-DD')).length,
      }
      return {
        list: todos.value,
        total: list.length,
        page: pageNum,
        pageSize: size,
        totalPages: Math.ceil(list.length / size),
      } as PageResult<Todo>
    } finally {
      loading.value = false
    }
  }

  async function fetchTodayTodos() {
    loading.value = true
    try {
      const data = await todoApi.getTodayTodos()
      todayTodos.value = data
      return data
    } catch (e) {
      console.warn('使用mock今日待办数据:', e)
      todayTodos.value = mockTodos.filter(t => t.dueDate === dayjs().format('YYYY-MM-DD')).slice(0, 3)
      return todayTodos.value
    } finally {
      loading.value = false
    }
  }

  async function fetchTodoDetail(id: string | number) {
    loading.value = true
    try {
      const data = await todoApi.getTodoDetail(id)
      currentTodo.value = data
      return data
    } catch (e) {
      console.warn('使用mock待办详情数据:', e)
      const found = mockTodos.find(item => String(item.id) === String(id))
      currentTodo.value = found || null
      return currentTodo.value
    } finally {
      loading.value = false
    }
  }

  async function fetchStats() {
    try {
      const data = await todoApi.getStats()
      stats.value = data
      return data
    } catch (e) {
      console.warn('使用mock统计数据:', e)
      const all = mockTodos
      stats.value = {
        total: all.length,
        completed: all.filter(t => t.completed).length,
        active: all.filter(t => !t.completed).length,
        today: all.filter(t => t.dueDate === dayjs().format('YYYY-MM-DD')).length,
      }
      return stats.value
    }
  }

  async function createTodo(data: Partial<Todo>) {
    loading.value = true
    try {
      const result = await todoApi.createTodo(data)
      todos.value.unshift(result)
      stats.value.total++
      if (!result.completed) {
        stats.value.active++
      } else {
        stats.value.completed++
      }
      return result
    } catch (e) {
      console.warn('使用mock创建待办:', e)
      const newTodo: Todo = {
        id: generateId(),
        title: data.title || '新待办',
        description: data.description || '',
        completed: false,
        priority: (data.priority as TodoPriority) || 'medium',
        dueDate: data.dueDate || dayjs().format('YYYY-MM-DD'),
        dueTime: data.dueTime,
        tags: data.tags || [],
        category: data.category || '工作',
        createdAt: new Date().toISOString(),
        contentType: data.contentType || 'text',
        voiceUrl: data.voiceUrl,
        voiceDuration: data.voiceDuration,
        hasReminder: data.hasReminder || false,
        remindTimeType: data.remindTimeType,
        remindRelativeMinutes: data.remindRelativeMinutes,
        repeatType: data.repeatType || 'none',
      }
      todos.value.unshift(newTodo)
      total.value++
      stats.value.total++
      stats.value.active++
      return newTodo
    } finally {
      loading.value = false
    }
  }

  async function updateTodo(id: string | number, data: Partial<Todo>) {
    loading.value = true
    try {
      const result = await todoApi.updateTodo(id, data)
      const index = todos.value.findIndex(t => t.id === id)
      if (index !== -1) {
        todos.value[index] = result
      }
      if (currentTodo.value?.id === id) {
        currentTodo.value = result
      }
      return result
    } catch (e) {
      console.warn('使用mock更新待办:', e)
      const index = todos.value.findIndex(t => t.id === id)
      if (index !== -1) {
        const wasCompleted = todos.value[index].completed
        todos.value[index] = { ...todos.value[index], ...data }
        if (data.completed !== undefined && data.completed !== wasCompleted) {
          if (data.completed) {
            stats.value.completed++
            stats.value.active--
          } else {
            stats.value.completed--
            stats.value.active++
          }
        }
        if (currentTodo.value?.id === id) {
          currentTodo.value = todos.value[index]
        }
        return todos.value[index]
      }
      return null
    } finally {
      loading.value = false
    }
  }

  async function deleteTodo(id: string | number) {
    loading.value = true
    try {
      await todoApi.deleteTodo(id)
      const todo = todos.value.find(t => t.id === id)
      todos.value = todos.value.filter(t => t.id !== id)
      if (todo) {
        stats.value.total--
        if (todo.completed) {
          stats.value.completed--
        } else {
          stats.value.active--
        }
      }
      if (currentTodo.value?.id === id) {
        currentTodo.value = null
      }
    } catch (e) {
      console.warn('使用mock删除待办:', e)
      const todo = todos.value.find(t => t.id === id)
      todos.value = todos.value.filter(t => t.id !== id)
      total.value--
      if (todo) {
        stats.value.total--
        if (todo.completed) {
          stats.value.completed--
        } else {
          stats.value.active--
        }
      }
      if (currentTodo.value?.id === id) {
        currentTodo.value = null
      }
    } finally {
      loading.value = false
    }
  }

  async function toggleTodo(id: string | number, completed: boolean) {
    try {
      const result = await todoApi.toggleTodo(id, completed)
      const index = todos.value.findIndex(t => t.id === id)
      if (index !== -1) {
        todos.value[index].completed = completed
      }
      if (completed) {
        stats.value.completed++
        stats.value.active--
      } else {
        stats.value.completed--
        stats.value.active++
      }
      return result
    } catch (e) {
      console.warn('使用mock切换待办状态:', e)
      const index = todos.value.findIndex(t => t.id === id)
      if (index !== -1) {
        todos.value[index].completed = completed
        if (completed) {
          stats.value.completed++
          stats.value.active--
        } else {
          stats.value.completed--
          stats.value.active++
        }
      }
      return todos.value[index]
    }
  }

  function clearCurrentTodo() {
    currentTodo.value = null
  }

  return {
    todos,
    todayTodos,
    currentTodo,
    loading,
    total,
    page,
    pageSize,
    stats,
    completedTodos,
    activeTodos,
    highPriorityTodos,
    fetchTodos,
    fetchTodayTodos,
    fetchTodoDetail,
    fetchStats,
    createTodo,
    updateTodo,
    deleteTodo,
    toggleTodo,
    clearCurrentTodo,
  }
})
