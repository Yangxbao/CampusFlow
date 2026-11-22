<template>
  <div class="page-container">
    <h2 class="page-title">扫码核销签到</h2>
    <div class="scan-grid">
      <div class="card">
        <h3>1. 选择活动</h3>
        <el-select v-model="activityId" placeholder="选择进行中的活动" style="width: 100%" @change="loadActs">
          <el-option v-for="a in acts" :key="a.id" :value="a.id" :label="a.title + '（' + a.statusDesc + '）'" />
        </el-select>
        <el-divider />
        <h3>2. 输入/粘贴签到凭证</h3>
        <p class="text-light">扫描学生二维码内容（或让学生出示凭证内容），粘贴到下方进行核销：</p>
        <el-input v-model="tokenInput" type="textarea" :rows="4" placeholder='{"activityId":...,"userId":...,"token":...}' />
        <el-button type="primary" class="mt16" style="width: 100%" size="large" :loading="scanning" @click="scan">
          核销签到
        </el-button>
      </div>
      <div class="card">
        <h3>核销结果</h3>
        <el-empty v-if="records.length === 0" description="暂无核销记录" />
        <el-table v-else :data="records" size="small">
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ row.time }}</template>
          </el-table-column>
          <el-table-column label="用户ID" prop="userId" width="180" />
          <el-table-column label="结果">
            <template #default="{ row }">
              <el-tag size="small" :type="row.ok ? 'success' : 'danger'">{{ row.message }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { organizerApi, checkinApi, type ActivityVO } from '@/api/modules'

const acts = ref<ActivityVO[]>([])
const activityId = ref<number>()
const tokenInput = ref('')
const scanning = ref(false)
const records = ref<{ time: string; userId: string; ok: boolean; message: string }[]>([])

onMounted(loadActs)

async function loadActs() {
  // 进行中/已发布的活动优先（签到窗口）
  const r = await organizerApi.myActivities({ pageNum: 1, pageSize: 50, status: 4 })
  const r2 = await organizerApi.myActivities({ pageNum: 1, pageSize: 50, status: 3 })
  acts.value = [...r.data.records, ...r2.data.records]
}

async function scan() {
  if (!activityId.value) {
    ElMessage.warning('请先选择活动')
    return
  }
  let token = tokenInput.value.trim()
  // 支持直接粘贴二维码 JSON，提取 token 字段
  try {
    const parsed = JSON.parse(token)
    if (parsed && parsed.token) {
      token = String(parsed.token)
      if (!activityId.value && parsed.activityId) {
        activityId.value = Number(parsed.activityId)
      }
    }
  } catch {
    // 非 JSON 视为裸 token
  }
  if (!token) {
    ElMessage.warning('请输入凭证内容')
    return
  }
  scanning.value = true
  try {
    const r = await checkinApi.scan({ activityId: activityId.value, token })
    records.value.unshift({ time: dayjs().format('HH:mm:ss'), userId: String(r.data.userId), ok: true, message: r.data.message })
    tokenInput.value = ''
  } catch (e) {
    const err = e as { code?: number; message?: string }
    records.value.unshift({ time: dayjs().format('HH:mm:ss'), userId: '-', ok: false, message: (err.code ?? '') + ' ' + (err.message || '核销失败') })
  } finally {
    scanning.value = false
  }
}
</script>

<style scoped>
.scan-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.mt16 { margin-top: 16px; }
@media (max-width: 900px) { .scan-grid { grid-template-columns: 1fr; } }
</style>
