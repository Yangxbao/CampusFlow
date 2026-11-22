<template>
  <div class="page-container">
    <h2 class="page-title">我的报名</h2>
    <div class="card">
      <el-tabs v-model="statusTab" @tab-change="load">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane label="已报名" name="1" />
        <el-tab-pane label="已签到" name="2" />
        <el-tab-pane label="已取消" name="3" />
        <el-tab-pane label="确认中" name="0" />
      </el-tabs>
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="activityTitle" label="活动" min-width="200" />
        <el-table-column prop="activityLocation" label="地点" width="140" />
        <el-table-column label="活动时间" width="170">
          <template #default="{ row }">{{ fmt(row.activityStartTime) }}</template>
        </el-table-column>
        <el-table-column label="报名时间" width="170">
          <template #default="{ row }">{{ fmt(row.registeredAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push('/activities/' + row.activityId)">详情</el-button>
            <el-button v-if="row.status === 1" link type="success" @click="showCredential(row.id)">签到凭证</el-button>
            <el-button v-if="row.status === 1" link type="danger" @click="cancel(row.id)">取消报名</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="load" />
      </div>
    </div>

    <el-dialog v-model="qrVisible" title="电子签到凭证" width="420px">
      <div v-if="qrData" class="qr-box">
        <img v-if="qrDataUrl" :src="qrDataUrl" alt="签到二维码" class="qr-img" />
        <p class="text-light">{{ qrData.activityTitle }}</p>
        <p class="text-light">有效期至：{{ fmt(qrData.expireAt) }}</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import QRCode from 'qrcode'
import { registrationApi, type RegistrationVO, type CheckinTokenVO } from '@/api/modules'

const list = ref<RegistrationVO[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const statusTab = ref('all')
const loading = ref(false)
const qrVisible = ref(false)
const qrData = ref<CheckinTokenVO | null>(null)
const qrDataUrl = ref('')

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'
}
function tagType(s: number) {
  return s === 1 ? 'primary' : s === 2 ? 'success' : s === 3 ? 'info' : 'warning'
}

async function load() {
  loading.value = true
  try {
    const status = statusTab.value === 'all' ? undefined : Number(statusTab.value)
    const r = await registrationApi.myList({ pageNum: pageNum.value, pageSize: pageSize.value, status })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

async function cancel(id: number) {
  try {
    await ElMessageBox.confirm('确定取消该报名？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await registrationApi.cancel(id)
  ElMessage.success('已取消')
  load()
}

async function showCredential(registrationId: number) {
  const r = await registrationApi.checkinToken(registrationId)
  qrData.value = r.data
  qrDataUrl.value = await QRCode.toDataURL(r.data.qrPayload, { width: 280, margin: 1 })
  qrVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
.qr-box { text-align: center; }
.qr-img { width: 280px; height: 280px; }
</style>
