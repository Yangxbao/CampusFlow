<template>
  <div class="page-container">
    <h2 class="page-title">系统操作审计</h2>
    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="username" label="操作人" width="140" />
        <el-table-column prop="action" label="动作" width="180" />
        <el-table-column prop="resource" label="资源" width="130" />
        <el-table-column prop="ip" label="IP" width="140" />
        <el-table-column prop="traceId" label="TraceId" min-width="200" />
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

interface AuditRow {
  id: number
  username?: string
  action: string
  resource?: string
  ip?: string
  traceId?: string
  createdAt: string
}

const list = ref<AuditRow[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
}

async function load() {
  loading.value = true
  try {
    const r = await adminApi.audits({ pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = r.data.records as unknown as AuditRow[]
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
