<template>
  <div class="page-container">
    <h2 class="page-title">活动审核</h2>
    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="title" label="活动" min-width="200" />
        <el-table-column prop="categoryName" label="分类" width="110" />
        <el-table-column prop="organizerName" label="组织者" width="140" />
        <el-table-column label="名额" width="90">
          <template #default="{ row }">{{ row.capacity }}</template>
        </el-table-column>
        <el-table-column label="活动时间" width="170">
          <template #default="{ row }">{{ fmt(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'warning' : 'info'">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" type="success" size="small" @click="approve(row.id)">通过</el-button>
            <el-button v-if="row.status === 1" type="danger" size="small" plain @click="reject(row.id)">拒绝</el-button>
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
import { adminApi, type ActivityVO } from '@/api/modules'

const list = ref<ActivityVO[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'
}

async function load() {
  loading.value = true
  try {
    const r = await adminApi.activities({ pageNum: pageNum.value, pageSize: pageSize.value, status: 1 })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

/** 审核通过：服务端在分布式锁内完成状态流转 + Redis 库存预热 */
async function approve(id: number) {
  const r = await adminApi.approve(id)
  ElMessage.success(r.message || '已通过并发布（库存预热完成）')
  load()
}

async function reject(id: number) {
  const { value } = await ElMessageBox.prompt('请输入拒绝原因', '审核拒绝', {
    inputPattern: /\S+/,
    inputErrorMessage: '原因不能为空'
  })
  await adminApi.reject(id, value)
  ElMessage.success('已拒绝')
  load()
}

onMounted(load)
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
