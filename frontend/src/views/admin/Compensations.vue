<template>
  <div class="page-container">
    <h2 class="page-title">异常任务与补偿记录</h2>
    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="bizType" label="类型" width="200" />
        <el-table-column prop="bizKey" label="业务键" min-width="220" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试" width="70" />
        <el-table-column prop="maxRetry" label="上限" width="70" />
        <el-table-column label="下次执行" width="170">
          <template #default="{ row }">{{ fmt(row.nextRetryTime) }}</template>
        </el-table-column>
        <el-table-column prop="lastError" label="最近错误" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 3 || row.status === 4" link type="primary" @click="requeue(row.id)">重新入队</el-button>
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
import { ElMessage } from 'element-plus'
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
  return s === 2 ? 'success' : s === 4 ? 'danger' : s === 3 ? 'warning' : 'info'
}
function statusText(s: number) {
  return ['待处理', '处理中', '成功', '失败可重试', '死信'][s] ?? '未知'
}

async function load() {
  loading.value = true
  try {
    const r = await adminApi.compensations({ pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

async function requeue(id: number) {
  await adminApi.requeue(id)
  ElMessage.success('已重新入队，等待补偿调度执行')
  load()
}

onMounted(load)
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
