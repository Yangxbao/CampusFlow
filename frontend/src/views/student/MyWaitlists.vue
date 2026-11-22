<template>
  <div class="page-container">
    <h2 class="page-title">候补队列</h2>
    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="activityTitle" label="活动" min-width="200" />
        <el-table-column prop="queueNo" label="排队号" width="90" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="前方人数" width="100">
          <template #default="{ row }">
            {{ row.status === 0 ? (row.aheadCount ?? '-') : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="入队时间" width="170">
          <template #default="{ row }">{{ fmt(row.joinTime) }}</template>
        </el-table-column>
        <el-table-column label="确认截止" width="170">
          <template #default="{ row }">{{ row.confirmDeadline ? fmt(row.confirmDeadline) : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" type="success" size="small" @click="confirm(row.id)">确认报名</el-button>
            <el-button v-if="row.status === 0 || row.status === 1" link type="danger" @click="cancel(row.id)">退出候补</el-button>
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
import dayjs from 'dayjs'
import { waitlistApi, type WaitlistVO } from '@/api/modules'

const list = ref<WaitlistVO[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'
}
function tagType(s: number) {
  return s === 0 ? 'warning' : s === 1 ? 'danger' : s === 2 ? 'success' : 'info'
}

async function load() {
  loading.value = true
  try {
    const r = await waitlistApi.myList({ pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

/** 晋级确认：走报名确认链路（预留 -> MQ -> 落库） */
async function confirm(id: number) {
  const r = await waitlistApi.confirm(id)
  ElMessage.success(r.data.message || '已确认，报名生效中')
  load()
}

async function cancel(id: number) {
  try {
    await ElMessageBox.confirm('确定退出候补？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await waitlistApi.cancel(id)
  ElMessage.success('已退出候补')
  load()
}

onMounted(load)
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
