<template>
  <el-container class="layout">
    <!-- 桌面端侧边栏 -->
    <el-aside v-if="!isMobile" width="220px" class="aside">
      <div class="logo" @click="$router.push('/home')">
        <el-icon :size="22" color="#4f6ef2"><Calendar /></el-icon>
        <span>校园活动平台</span>
      </div>
      <el-menu :default-active="route.path" router class="menu" background-color="#ffffff" text-color="#4b5563" active-text-color="#4f6ef2">
        <!-- 通用 -->
        <el-menu-item index="/home"><el-icon><HomeFilled /></el-icon>活动广场</el-menu-item>
        <template v-if="auth.isStudent">
          <el-menu-item index="/my/registrations"><el-icon><Tickets /></el-icon>我的报名</el-menu-item>
          <el-menu-item index="/my/waitlists"><el-icon><Clock /></el-icon>候补队列</el-menu-item>
        </template>
        <template v-if="auth.isOrganizer || auth.isAdmin">
          <el-menu-item index="/organizer/workbench"><el-icon><Platform /></el-icon>组织者工作台</el-menu-item>
          <el-menu-item index="/organizer/scan"><el-icon><Cellphone /></el-icon>扫码核销</el-menu-item>
        </template>
        <template v-if="auth.isAdmin">
          <el-menu-item index="/admin/dashboard"><el-icon><DataAnalysis /></el-icon>系统仪表盘</el-menu-item>
          <el-menu-item index="/admin/review"><el-icon><Stamp /></el-icon>活动审核</el-menu-item>
          <el-menu-item index="/admin/users"><el-icon><User /></el-icon>用户管理</el-menu-item>
          <el-menu-item index="/admin/messages"><el-icon><Message /></el-icon>消息状态</el-menu-item>
          <el-menu-item index="/admin/compensations"><el-icon><Warning /></el-icon>补偿任务</el-menu-item>
          <el-menu-item index="/admin/audits"><el-icon><Document /></el-icon>操作审计</el-menu-item>
        </template>
        <el-menu-item index="/my/notifications">
          <el-icon><Bell /></el-icon>
          <template #title>消息通知<el-badge v-if="unread > 0" :value="unread" :max="99" class="badge" /></template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 移动端抽屉菜单（汉堡按钮唤起） -->
    <el-drawer v-model="drawerOpen" direction="ltr" :size="250" :with-header="false" :append-to-body="true">
      <div class="drawer-inner">
        <div class="logo" @click="goAndClose('/home')">
          <el-icon :size="22" color="#4f6ef2"><Calendar /></el-icon>
          <span>校园活动平台</span>
        </div>
        <el-menu :default-active="route.path" router class="menu" background-color="#ffffff" text-color="#4b5563" active-text-color="#4f6ef2" @select="drawerOpen = false">
          <el-menu-item index="/home"><el-icon><HomeFilled /></el-icon>活动广场</el-menu-item>
          <template v-if="auth.isStudent">
            <el-menu-item index="/my/registrations"><el-icon><Tickets /></el-icon>我的报名</el-menu-item>
            <el-menu-item index="/my/waitlists"><el-icon><Clock /></el-icon>候补队列</el-menu-item>
          </template>
          <template v-if="auth.isOrganizer || auth.isAdmin">
            <el-menu-item index="/organizer/workbench"><el-icon><Platform /></el-icon>组织者工作台</el-menu-item>
            <el-menu-item index="/organizer/scan"><el-icon><Cellphone /></el-icon>扫码核销</el-menu-item>
          </template>
          <template v-if="auth.isAdmin">
            <el-menu-item index="/admin/dashboard"><el-icon><DataAnalysis /></el-icon>系统仪表盘</el-menu-item>
            <el-menu-item index="/admin/review"><el-icon><Stamp /></el-icon>活动审核</el-menu-item>
            <el-menu-item index="/admin/users"><el-icon><User /></el-icon>用户管理</el-menu-item>
            <el-menu-item index="/admin/messages"><el-icon><Message /></el-icon>消息状态</el-menu-item>
            <el-menu-item index="/admin/compensations"><el-icon><Warning /></el-icon>补偿任务</el-menu-item>
            <el-menu-item index="/admin/audits"><el-icon><Document /></el-icon>操作审计</el-menu-item>
          </template>
          <el-menu-item index="/my/notifications">
            <el-icon><Bell /></el-icon>
            <template #title>消息通知<el-badge v-if="unread > 0" :value="unread" :max="99" class="badge" /></template>
          </el-menu-item>
        </el-menu>
      </div>
    </el-drawer>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <!-- 移动端：汉堡按钮 + 品牌；桌面端：隐藏（品牌在侧边栏） -->
          <el-icon v-if="isMobile" :size="22" class="hamburger" @click="drawerOpen = true"><Menu /></el-icon>
          <div v-if="isMobile" class="logo-mini" @click="$router.push('/home')">
            <el-icon :size="18" color="#4f6ef2"><Calendar /></el-icon>
            <span>校园活动平台</span>
          </div>
          <div v-else class="header-title">高校校园活动报名与智能签到平台</div>
        </div>
        <div class="header-right">
          <!-- 点击头像进入个人中心（替代原侧边栏"个人中心"入口） -->
          <div class="avatar-wrap" title="个人中心" @click="$router.push('/my/profile')">
            <el-avatar :size="isMobile ? 30 : 34" :src="avatarUrl" class="avatar">
              <el-icon :size="18"><UserFilled /></el-icon>
            </el-avatar>
            <span v-if="!isMobile" class="user-name">{{ auth.user?.nickname || auth.user?.username }}</span>
          </div>
          <el-tag size="small" type="primary" class="role-tag">{{ roleLabel }}</el-tag>
          <el-button link type="danger" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { notifyApi } from '@/api/modules'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const unread = ref(0)
const drawerOpen = ref(false)
let timer: number | undefined

/** 移动端判定（<=768px）：侧边栏切换为抽屉，头部切换为紧凑形态 */
const isMobile = ref(window.innerWidth <= 768)
let resizeHandler: () => void

const roleLabel = computed(() => {
  if (auth.isAdmin) return '管理员'
  if (auth.isOrganizer) return '组织者'
  return '学生'
})

/** 头像 URL（后端返回相对路径 /api/v1/files/...；未上传时不带 src 走默认图标） */
const avatarUrl = computed(() => {
  const a = auth.user?.avatar
  return a && a.trim() !== '' ? a : undefined
})

async function refreshUnread() {
  try {
    const r = await notifyApi.unreadCount()
    unread.value = Number(r.data) || 0
  } catch {
    /* 忽略轮询错误 */
  }
}

function goAndClose(path: string) {
  drawerOpen.value = false
  router.push(path)
}

function logout() {
  auth.logout()
  router.push('/login')
}

onMounted(() => {
  refreshUnread()
  auth.refreshMe()
  timer = window.setInterval(refreshUnread, 30000)
  resizeHandler = () => {
    isMobile.value = window.innerWidth <= 768
    if (!isMobile.value) drawerOpen.value = false
  }
  window.addEventListener('resize', resizeHandler)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  window.removeEventListener('resize', resizeHandler)
})
</script>

<style scoped>
.layout { height: 100%; }
.aside {
  background: #ffffff;
  border-right: 1px solid #eef0f5;
}
.drawer-inner { height: 100%; }
.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 700;
  color: #1f2937;
  padding: 20px 24px 16px;
  cursor: pointer;
  white-space: nowrap;
}
.menu { border-right: none; }
.badge { margin-left: 6px; }
.header {
  background: #ffffff;
  border-bottom: 1px solid #eef0f5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}
.header-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.hamburger { cursor: pointer; color: #374151; }
.logo-mini {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
}
.header-title { font-size: 14px; color: #6b7280; }
.header-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.avatar-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
  transition: background 0.15s ease;
}
.avatar-wrap:hover { background: #f0f3ff; }
.avatar { background: #4f6ef2; }
.user-name { font-size: 14px; }
.main { padding: 0; background: var(--cf-bg); }

/* 移动端紧凑化 */
@media (max-width: 768px) {
  .header { height: 52px; }
  .role-tag { display: none; }
}
</style>
