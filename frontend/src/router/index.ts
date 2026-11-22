/**
 * 路由：三端页面 + 登录守卫 + 角色守卫（前端路由仅做体验引导，安全边界在服务端）。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  { path: '/register', name: 'register', component: () => import('@/views/Register.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/views/layout/MainLayout.vue'),
    children: [
      { path: '', redirect: '/home' },
      // 学生端
      { path: 'home', name: 'home', component: () => import('@/views/student/Home.vue'), meta: { roles: ['STUDENT', 'ORGANIZER', 'ADMIN'] } },
      { path: 'activities/:id', name: 'activityDetail', component: () => import('@/views/student/ActivityDetail.vue'), meta: { roles: ['STUDENT', 'ORGANIZER', 'ADMIN'] } },
      { path: 'my/registrations', name: 'myRegistrations', component: () => import('@/views/student/MyRegistrations.vue'), meta: { roles: ['STUDENT'] } },
      { path: 'my/waitlists', name: 'myWaitlists', component: () => import('@/views/student/MyWaitlists.vue'), meta: { roles: ['STUDENT'] } },
      { path: 'my/notifications', name: 'notifications', component: () => import('@/views/student/Notifications.vue'), meta: { roles: ['STUDENT', 'ORGANIZER', 'ADMIN'] } },
      { path: 'my/profile', name: 'profile', component: () => import('@/views/student/Profile.vue'), meta: { roles: ['STUDENT', 'ORGANIZER', 'ADMIN'] } },
      // 组织者端
      { path: 'organizer/workbench', name: 'workbench', component: () => import('@/views/organizer/Workbench.vue'), meta: { roles: ['ORGANIZER', 'ADMIN'] } },
      { path: 'organizer/activities/new', name: 'activityNew', component: () => import('@/views/organizer/ActivityEdit.vue'), meta: { roles: ['ORGANIZER', 'ADMIN'] } },
      { path: 'organizer/activities/:id/edit', name: 'activityEdit', component: () => import('@/views/organizer/ActivityEdit.vue'), meta: { roles: ['ORGANIZER', 'ADMIN'] } },
      { path: 'organizer/activities/:id/registrants', name: 'registrants', component: () => import('@/views/organizer/Registrants.vue'), meta: { roles: ['ORGANIZER', 'ADMIN'] } },
      { path: 'organizer/activities/:id/waitlists', name: 'waitlistManage', component: () => import('@/views/organizer/WaitlistManage.vue'), meta: { roles: ['ORGANIZER', 'ADMIN'] } },
      { path: 'organizer/activities/:id/stats', name: 'organizerStats', component: () => import('@/views/organizer/OrganizerStats.vue'), meta: { roles: ['ORGANIZER', 'ADMIN'] } },
      { path: 'organizer/scan', name: 'scanCheckin', component: () => import('@/views/organizer/ScanCheckin.vue'), meta: { roles: ['ORGANIZER', 'ADMIN'] } },
      // 管理员端
      { path: 'admin/dashboard', name: 'adminDashboard', component: () => import('@/views/admin/Dashboard.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'admin/users', name: 'adminUsers', component: () => import('@/views/admin/UserManage.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'admin/review', name: 'adminReview', component: () => import('@/views/admin/ActivityReview.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'admin/audits', name: 'adminAudits', component: () => import('@/views/admin/AuditLogs.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'admin/compensations', name: 'adminCompensations', component: () => import('@/views/admin/Compensations.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'admin/messages', name: 'adminMessages', component: () => import('@/views/admin/MessageStatus.vue'), meta: { roles: ['ADMIN'] } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  const roles = to.meta.roles as string[] | undefined
  if (roles && roles.length > 0) {
    const has = auth.user?.roles.some((r) => roles.includes(r))
    // 角色判定：学生默认拥有 STUDENT 角色；后端 roles 为空时按学生处理
    const isDefaultStudent = (auth.user?.roles.length ?? 0) === 0 && roles.includes('STUDENT')
    if (!has && !isDefaultStudent) {
      ElMessage.warning('您没有访问该页面的权限')
      return { path: '/home' }
    }
  }
  return true
})

export default router
