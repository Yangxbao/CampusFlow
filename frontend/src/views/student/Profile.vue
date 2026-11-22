<template>
  <div class="page-container">
    <h2 class="page-title">个人中心</h2>
    <div class="profile-grid">
      <div class="card">
        <h3>基本信息</h3>
        <!-- 头像上传（点击头像或按钮选择图片，即选即传） -->
        <div class="avatar-block">
          <el-upload :show-file-list="false" :http-request="uploadAvatar" accept="image/*" class="avatar-uploader">
            <el-avatar :size="88" :src="avatarUrl" class="profile-avatar">
              <el-icon :size="40"><UserFilled /></el-icon>
            </el-avatar>
            <div class="avatar-tip">点击头像更换</div>
          </el-upload>
          <el-tag v-if="uploading" size="small" type="info">上传中...</el-tag>
        </div>
        <el-form :model="form" label-width="80px" class="mt16">
          <el-form-item label="用户名"><el-input v-model="form.username" disabled /></el-form-item>
          <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
          <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
          <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
          </el-form-item>
        </el-form>
      </div>
      <div class="card">
        <h3>修改密码</h3>
        <el-form :model="pwd" label-width="80px" class="mt16">
          <el-form-item label="原密码"><el-input v-model="pwd.oldPassword" type="password" show-password /></el-form-item>
          <el-form-item label="新密码"><el-input v-model="pwd.newPassword" type="password" show-password /></el-form-item>
          <el-form-item>
            <el-button type="warning" plain :loading="changing" @click="changePwd">修改密码</el-button>
          </el-form-item>
        </el-form>
        <el-divider />
        <div class="text-light">我的角色：{{ roleText }}</div>
        <div class="text-light mt8">注册时间：{{ fmt(user?.createdAt) }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import type { UploadRequestOptions } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { profileApi } from '@/api/modules'

const auth = useAuthStore()
const form = reactive({ username: '', nickname: '', email: '', phone: '' })
const pwd = reactive({ oldPassword: '', newPassword: '' })
const saving = ref(false)
const changing = ref(false)
const uploading = ref(false)

const user = computed(() => auth.user)
const roleText = computed(() => {
  const roles = auth.user?.roles ?? []
  if (roles.includes('ADMIN')) return '管理员'
  if (roles.includes('ORGANIZER')) return '组织者'
  return '学生'
})

/** 头像 URL：后端返回相对路径；加时间戳参数破缓存保证立即生效 */
const avatarUrl = computed(() => {
  const a = auth.user?.avatar
  return a && a.trim() !== '' ? a + '?t=' + (auth.user?.id ?? '') : undefined
})

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'
}

async function load() {
  const r = await profileApi.get()
  form.username = r.data.username
  form.nickname = r.data.nickname
  form.email = r.data.email || ''
  form.phone = r.data.phone || ''
}

/** 自定义上传：FormData -> POST /my/profile/avatar；成功后刷新用户信息（头像立即可见） */
async function uploadAvatar(options: UploadRequestOptions) {
  const file = options.file as File
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 2MB')
    return
  }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    await profileApi.uploadAvatar(fd)
    await auth.refreshMe()
    ElMessage.success('头像已更新')
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await profileApi.update({ nickname: form.nickname, email: form.email, phone: form.phone })
    await auth.refreshMe()
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}

async function changePwd() {
  if (!pwd.oldPassword || !pwd.newPassword) {
    ElMessage.warning('请填写完整')
    return
  }
  changing.value = true
  try {
    await profileApi.changePassword(pwd.oldPassword, pwd.newPassword)
    ElMessage.success('密码修改成功，请使用新密码重新登录')
    pwd.oldPassword = ''
    pwd.newPassword = ''
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message || '修改失败')
  } finally {
    changing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.profile-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.mt8 { margin-top: 8px; }
.avatar-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
}
.avatar-uploader { text-align: center; }
.profile-avatar {
  background: #4f6ef2;
  cursor: pointer;
  transition: box-shadow 0.15s ease;
}
.profile-avatar:hover { box-shadow: 0 0 0 4px rgba(79, 110, 242, 0.15); }
.avatar-tip { font-size: 12px; color: #6b7280; margin-top: 6px; }
@media (max-width: 900px) { .profile-grid { grid-template-columns: 1fr; } }
</style>
