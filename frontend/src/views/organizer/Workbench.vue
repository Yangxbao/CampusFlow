<template>
  <div class="page-container">
    <div class="flex-between mb16">
      <h2 class="page-title">组织者工作台</h2>
      <el-button type="primary" :icon="Plus" @click="$router.push('/organizer/activities/new')">创建活动</el-button>
    </div>
    <div class="card">
      <el-tabs v-model="statusTab" @tab-change="load">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane label="草稿" name="0" />
        <el-tab-pane label="待审核" name="1" />
        <el-tab-pane label="已发布" name="3" />
        <el-tab-pane label="进行中" name="4" />
        <el-tab-pane label="已结束" name="5" />
        <el-tab-pane label="已取消" name="6" />
      </el-tabs>
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="title" label="活动" min-width="220" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名额" width="110">
          <template #default="{ row }">{{ row.confirmedCount }} / {{ row.capacity }}</template>
        </el-table-column>
        <el-table-column label="活动时间" width="170">
          <template #default="{ row }">{{ fmt(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="430" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0 || row.status === 2" link type="primary" @click="$router.push('/organizer/activities/' + row.id + '/edit')">编辑</el-button>
            <el-button v-if="row.status === 0 || row.status === 2" link type="success" @click="submitReview(row.id)">提交审核</el-button>
            <el-button v-if="row.status === 3" link type="warning" @click="adjustCapacity(row)">调整名额</el-button>
            <el-button link type="primary" @click="$router.push('/organizer/activities/' + row.id + '/registrants')">报名人员</el-button>
            <el-button link type="primary" @click="$router.push('/organizer/activities/' + row.id + '/waitlists')">候补</el-button>
            <el-button link type="primary" @click="$router.push('/organizer/activities/' + row.id + '/stats')">统计</el-button>
            <el-button v-if="row.status === 3 || row.status === 4" link type="danger" @click="cancelActivity(row.id)">取消活动</el-button>
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
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { organizerApi, type ActivityVO } from '@/api/modules'

const list = ref<ActivityVO[]>([])
const statusTab = ref('all')
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'
}
function tagType(s: number) {
  return s === 3 ? 'primary' : s === 4 ? 'success' : s === 5 ? 'info' : s === 6 ? 'danger' : 'warning'
}

async function load() {
  loading.value = true
  try {
    const status = statusTab.value === 'all' ? undefined : Number(statusTab.value)
    const r = await organizerApi.myActivities({ pageNum: pageNum.value, pageSize: pageSize.value, status })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

async function submitReview(id: number) {
  await organizerApi.submit(id)
  ElMessage.success('已提交审核，等待管理员审批')
  load()
}

async function adjustCapacity(row: ActivityVO) {
  const { value } = await ElMessageBox.prompt('请输入新的名额上限（当前 ' + row.capacity + '）', '调整名额', {
    inputValue: String(row.capacity),
    inputPattern: /^\d+$/,
    inputErrorMessage: '请输入数字'
  })
  await organizerApi.adjustCapacity(row.id, Number(value))
  ElMessage.success('名额已调整，Redis 库存已重算')
  load()
}

async function cancelActivity(id: number) {
  try {
    await ElMessageBox.confirm('取消活动将通知全部已报名用户，确定？', '取消活动', { type: 'warning' })
  } catch {
    return
  }
  await organizerApi.cancel(id)
  ElMessage.success('活动已取消')
  load()
}

onMounted(load)
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
