<template>
  <div class="page-container">
    <h2 class="page-title">用户管理</h2>
    <div class="card">
      <el-input v-model="keyword" placeholder="搜索用户名/昵称" style="width: 300px" clearable @keyup.enter="load">
        <template #append><el-button @click="load">搜索</el-button></template>
      </el-input>
      <el-table :data="list" v-loading="loading" stripe class="mt16">
        <el-table-column prop="username" label="用户名" width="160" />
        <el-table-column prop="nickname" label="昵称" width="160" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }">{{ roleText(row.roles) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 0 ? 'success' : 'danger'">{{ row.status === 0 ? '正常' : '已禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="danger" @click="toggle(row, true)">禁用</el-button>
            <el-button v-else link type="success" @click="toggle(row, false)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="load" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, type UserVO } from '@/api/modules'

const list = ref<(UserVO & { status: number })[]>([])
const keyword = ref('')
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)

function roleText(roles?: string[]) {
  if (roles?.includes('ADMIN')) return '管理员'
  if (roles?.includes('ORGANIZER')) return '组织者'
  return '学生'
}

async function load() {
  loading.value = true
  try {
    const r = await adminApi.users({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value || undefined })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

async function toggle(row: UserVO, disabled: boolean) {
  try {
    await ElMessageBox.confirm((disabled ? '禁用' : '启用') + '用户 ' + row.username + '？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await adminApi.setUserStatus(row.id, disabled)
  ElMessage.success('已' + (disabled ? '禁用' : '启用'))
  load()
}

onMounted(load)
</script>

<style scoped>
.mt16 { margin-top: 16px; }
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
