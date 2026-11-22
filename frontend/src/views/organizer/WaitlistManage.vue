<template>
  <div class="page-container">
    <div class="flex-between mb16">
      <h2 class="page-title">候补人员管理</h2>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="queueNo" label="排队号" width="90" />
        <el-table-column prop="userId" label="用户ID" width="180" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="入队时间" width="170">
          <template #default="{ row }">{{ fmt(row.joinTime) }}</template>
        </el-table-column>
        <el-table-column label="晋级时间" width="170">
          <template #default="{ row }">{{ row.promotedAt ? fmt(row.promotedAt) : '-' }}</template>
        </el-table-column>
        <el-table-column label="确认截止" width="170">
          <template #default="{ row }">{{ row.confirmDeadline ? fmt(row.confirmDeadline) : '-' }}</template>
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
import dayjs from 'dayjs'
import { organizerApi, type WaitlistVO } from '@/api/modules'

const route = useRoute()
const id = route.params.id as string
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
    const r = await organizerApi.waitlists(Number(id), { pageNum: pageNum.value, pageSize: pageSize.value })
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
