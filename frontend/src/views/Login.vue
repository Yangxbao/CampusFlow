<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <el-icon :size="36" color="#4f6ef2"><Calendar /></el-icon>
        <h1>校园活动平台</h1>
        <p>高校校园活动报名、预约与智能签到平台</p>
      </div>
      <el-form :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="用户名 / 学号" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="密码" size="large" />
        </el-form-item>
        <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="submit">登 录</el-button>
        <div class="extra">
          <span>还没有账号？</span>
          <el-button link type="primary" @click="$router.push('/register')">立即注册</el-button>
        </div>
      </el-form>
      <el-divider />
      <div class="tips">
        <p class="text-light">测试账号（种子数据）：</p>
        <p class="text-light">管理员 admin / admin123456</p>
        <p class="text-light">组织者 organizer1 / org123456</p>
        <p class="text-light">学生 student01 / stu123456</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/home'
    router.push(redirect)
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef1ff 0%, #f5f7fb 50%, #e8fff4 100%);
}
.login-card {
  width: min(400px, 92vw);
  background: #ffffff;
  border-radius: 16px;
  padding: 36px 40px;
  box-shadow: 0 8px 30px rgba(79, 110, 242, 0.12);
}
.brand { text-align: center; margin-bottom: 24px; }
.brand h1 { margin: 8px 0 4px; font-size: 24px; }
.brand p { margin: 0; color: #6b7280; font-size: 13px; }
.login-btn { width: 100%; margin-top: 8px; }
.extra { text-align: center; margin-top: 12px; font-size: 13px; color: #6b7280; }
.tips { font-size: 12px; line-height: 1.8; }
</style>
