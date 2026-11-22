<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <el-icon :size="36" color="#4f6ef2"><Calendar /></el-icon>
        <h1>注册校园活动平台</h1>
        <p>加入校园活动平台，探索精彩活动</p>
      </div>
      <el-form :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item label="用户名（4-32 位字母数字下划线）">
          <el-input v-model="form.username" placeholder="例如 student2026" size="large" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="展示名称" size="large" />
        </el-form-item>
        <el-form-item label="密码（6-32 位）">
          <el-input v-model="form.password" type="password" show-password size="large" />
        </el-form-item>
        <el-form-item label="注册身份">
          <el-radio-group v-model="form.role">
            <el-radio value="STUDENT">学生</el-radio>
            <el-radio value="ORGANIZER">组织者（社团/部门）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="submit">注 册</el-button>
        <div class="extra">
          <span>已有账号？</span>
          <el-button link type="primary" @click="$router.push('/login')">去登录</el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', nickname: '', password: '', role: 'STUDENT' })

async function submit() {
  if (!form.username || !form.nickname || !form.password) {
    ElMessage.warning('请完整填写注册信息')
    return
  }
  loading.value = true
  try {
    await auth.register(form)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message || '注册失败')
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
  width: min(420px, 92vw);
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
</style>
