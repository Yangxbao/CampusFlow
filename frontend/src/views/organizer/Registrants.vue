<template>
  <div class="page-container">
    <div class="flex-between mb16">
      <h2 class="page-title">报名人员</h2>
      <div>
        <el-button :loading="exporting" @click="exportCsv">导出 CSV</el-button>
        <el-button @click="$router.back()">返回</el-button>
      </div>
    </div>
    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="nickname" label="昵称" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 2 ? 'success' : row.status === 1 ? 'primary' : 'info'">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="报名时间" width="170">
          <template #default="{ row }">{{ fmt(row.registeredAt) }}</template>
        </el-table-column>
        <el-table-column label="签到时间" width="170">
          <template #default="{ row }">{{ row.checkinAt ? fmt(row.checkinAt) : '-' }}</template>
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
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { organizerApi, type RegistrationVO } from '@/api/modules'

const route = useRoute()
const id = route.params.id as string
const list = ref<RegistrationVO[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'
}

async function load() {
  loading.value = true
  try {
    const r = await organizerApi.registrations(Number(id), { pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

/** 异步导出：提交任务 -> 轮询状态 -> 完成后下载 */
async function exportCsv() {
  exporting.value = true
  try {
    const r = await organizerApi.export(Number(id))
    const taskId = r.data.id
    ElMessage.info('导出任务已提交...')
    for (let i = 0; i < 30; i++) {
      const st = await organizerApi.exportStatus(taskId)
      if (st.data.status === 2) {
        ElMessage.success('导出完成，共 ' + (st.data.rowCount ?? 0) + ' 行，开始下载')
        window.open('/api/v1/organizer/exports/' + taskId + '/download', '_blank')
        return
      }
      if (st.data.status === 3) {
        ElMessage.error('导出失败：' + (st.data.error || '未知错误'))
        return
      }
      await new Promise((resolve) => setTimeout(resolve, 1000))
    }
    ElMessage.warning('导出超时，请稍后在任务列表查看')
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
