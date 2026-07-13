import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'
import HomePage from '@/pages/HomePage.vue'
import WeatherPage from '@/pages/WeatherPage.vue'
import TravelPage from '@/pages/TravelPage.vue'
import TravelDetailPage from '@/pages/TravelDetailPage.vue'
import RemindersPage from '@/pages/RemindersPage.vue'
import TodosPage from '@/pages/TodosPage.vue'
import BabyPage from '@/pages/BabyPage.vue'
import VaccinePage from '@/pages/VaccinePage.vue'
import AlbumListPage from '@/pages/AlbumListPage.vue'
import AlbumDetailPage from '@/pages/AlbumDetailPage.vue'
import LoginPage from '@/pages/LoginPage.vue'
import ProfilePage from '@/pages/ProfilePage.vue'
import FamilyMembersPage from '@/pages/FamilyMembersPage.vue'
import InviteCodePage from '@/pages/InviteCodePage.vue'
import NotificationSettingsPage from '@/pages/NotificationSettingsPage.vue'
import ItemsPage from '@/pages/ItemsPage.vue'
import ItemDetailPage from '@/pages/ItemDetailPage.vue'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginPage,
  },
  {
    path: '/',
    component: MainLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: HomePage,
      },
      {
        path: 'weather',
        name: 'weather',
        component: WeatherPage,
      },
      {
        path: 'travel',
        name: 'travel',
        component: TravelPage,
      },
      {
        path: 'travel/:id',
        name: 'travel-detail',
        component: TravelDetailPage,
        meta: {
          hideTabBar: true,
        },
      },
      {
        path: 'reminders',
        name: 'reminders',
        component: RemindersPage,
      },
      {
        path: 'todos',
        name: 'todos',
        component: TodosPage,
      },
      {
        path: 'baby',
        name: 'baby',
        component: BabyPage,
      },
      {
        path: 'baby/:id/vaccine',
        name: 'baby-vaccine',
        component: VaccinePage,
        meta: {
          hideTabBar: true,
        },
      },
      {
        path: 'baby/:id/albums',
        name: 'baby-albums',
        component: AlbumListPage,
        meta: {
          hideTabBar: true,
        },
      },
      {
        path: 'baby/:id/albums/:albumId',
        name: 'baby-album-detail',
        component: AlbumDetailPage,
        meta: {
          hideTabBar: true,
        },
      },
      {
        path: 'profile',
        name: 'profile',
        component: ProfilePage,
      },
      {
        path: 'family/members',
        name: 'family-members',
        component: FamilyMembersPage,
        meta: {
          hideTabBar: true,
        },
      },
      {
        path: 'family/invite',
        name: 'family-invite',
        component: InviteCodePage,
        meta: {
          hideTabBar: true,
        },
      },
      {
        path: 'settings/notification',
        name: 'settings-notification',
        component: NotificationSettingsPage,
        meta: {
          hideTabBar: true,
        },
      },
      {
        path: 'items',
        name: 'items',
        component: ItemsPage,
        meta: {
          hideTabBar: true,
        },
      },
      {
        path: 'items/:id',
        name: 'item-detail',
        component: ItemDetailPage,
        meta: {
          hideTabBar: true,
        },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  if (to.path !== '/login' && !authStore.isLoggedIn) {
    next('/login')
  } else if (to.path === '/login' && authStore.isLoggedIn) {
    next('/')
  } else {
    next()
  }
})

export default router
