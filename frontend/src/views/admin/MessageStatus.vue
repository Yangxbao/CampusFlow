<template>
  <div class="page-container">
    <h2 class="page-title">消息处理状态（本地消息表）</h2>
    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="msgKey" label="消息键" min-width="200" />
        <el-table-column prop="topic" label="Topic" min-width="220" />
        <el-table-column prop="bizType" label="业务" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试" width="70" />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="lastError" label="错误" min-width="160" show-overflow-tooltip />
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="load" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { adminApi } from '@/api/modules'

const list = ref<Record<string, unknown>[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)

function fmt(t?: string) {
  return t ? dayjs(String(t)).format('YYYY-MM-DD HH:mm:ss') : '-'
}
function statusType(s: number) {
  return s === 2 ? 'success' : s === 3 ? 'danger' : s === 1 ? 'primary' : 'info'
}
function statusText(s: number) {
  return ['待发送', '已发送', '消费方已确认', '发送失败'][s] ?? '未知'
}

async function load() {
  loading.value = true
  try {
    const r = await adminApi.messages({ pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
