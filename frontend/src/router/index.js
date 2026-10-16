import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import AppLayout from '../layouts/AppLayout.vue'
import { safeNext } from '../utils/safeNext'

const routes = [
  { path: '/forgot-password', component: () => import('../views/PasswordRecoveryView.vue'), meta: { public: true } },
  { path: '/reset-password', component: () => import('../views/PasswordRecoveryView.vue'), meta: { public: true } },
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { public: true }
  },
  // Change 11：微信绑定/登录承接页（公开路由，token 经 fragment 或票据流转，不走登录守卫）
  {
    path: '/wechat/bind',
    name: 'wechat-bind',
    component: () => import('../views/WechatBindView.vue'),
    meta: { public: true }
  },
  {
    path: '/wechat/callback',
    name: 'wechat-callback',
    component: () => import('../views/WechatCallbackView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: AppLayout,
    meta: { requiresAuth: true },
    children: [
      { path: 'daily', name: 'daily', component: () => import('../views/DailyView.vue'), meta: { title: '我的一天', familyOptional: true } },
      { path: '', redirect: '/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/DashboardView.vue'),
        meta: { title: '首页总览', icon: '🏠' }
      },
      { path: 'agent/draft/:id', name: 'agent-draft', component: () => import('../views/AgentDraftDetailView.vue'), meta: { title: '确认待办' } },
      { path: 'tasks', name: 'tasks', component: () => import('../views/TaskView.vue') },
      // 创建任务独立页（从任务列表点「创建任务」按钮进入，老人更聚焦）
      { path: 'tasks/new', name: 'task-new', component: () => import('../views/TaskCreateView.vue'), meta: { title: '创建任务' } },
      // 编辑任务独立页（从任务卡片点「✏️ 编辑」按钮进入）
      { path: 'tasks/:id/edit', name: 'task-edit', component: () => import('../views/TaskEditView.vue'), meta: { title: '编辑任务' } },
      // Change 13：日历日程页（懒加载，meta 供菜单/标题复用）
      { path: 'calendar', name: 'calendar', component: () => import('../views/CalendarView.vue'), meta: { title: '日程', icon: '📅' } },
      { path: 'profiles', name: 'profiles', component: () => import('../views/PersonProfileView.vue'), meta: { title: '人员档案', icon: '👥', requiresAuth: true } },
      { path: 'chat', name: 'chat', component: () => import('../views/DecomposeChatPageWrapper.vue'), meta: { title: 'AI拆解', icon: '🤖', requiresAuth: true } },
      { path: 'work-tasks', name: 'work-tasks', component: () => import('../views/WorkTasksView.vue'), meta: { title: '工作任务', icon: '💼', requiresAuth: true, familyOptional: true } },
      { path: 'more', name: 'more', component: () => import('../views/MoreMenuView.vue'), meta: { title: '更多功能', familyOptional: true } },
      { path: 'templates', name: 'templates', component: () => import('../views/TemplatesView.vue'), meta: { title: '模板管理', icon: '📋' } },
      { path: 'automation', name: 'automation', component: () => import('../views/AutomationView.vue'), meta: { title: '自动化', icon: '📈' } },
      { path: 'categories', name: 'categories', component: () => import('../views/CategoryView.vue') },
      { path: 'families', name: 'families', component: () => import('../views/FamilyView.vue'), meta: { familyOptional: true } },
      { path: 'families/:id/invites', name: 'invites', component: () => import('../views/InviteView.vue') },
      // 修改密码（从「更多」聚合页进入）
      { path: 'password', name: 'password', component: () => import('../views/PasswordView.vue'), meta: { title: '修改密码', familyOptional: true } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('accessToken')
  if (to.meta.requiresAuth && !token) {
    next({ path: '/login', query: { next: safeNext(to.fullPath) } })
  } else if (to.path === '/login' && token) {
    next('/')
  } else {
    next()
  }
})

export default router
