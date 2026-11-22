<template>
  <div class="page-container" v-loading="loading">
    <el-page-header class="mb16" @back="$router.back()">
      <template #content>活动详情</template>
    </el-page-header>
    <template v-if="detail">
      <div class="card">
        <div class="flex-between">
          <div>
            <h2 class="page-title">{{ detail.title }}</h2>
            <div class="tags">
              <el-tag size="small" effect="plain">{{ detail.categoryName }}</el-tag>
              <el-tag size="small" :type="statusType(detail.status)">{{ detail.statusDesc }}</el-tag>
            </div>
          </div>
          <div class="remain">
            <template v-if="detail.remaining !== null && detail.remaining !== undefined">
              <span class="remain-num">{{ detail.remaining }}</span> / {{ detail.capacity }} 剩余名额
            </template>
            <span v-else class="text-light">名额加载中...</span>
          </div>
        </div>

        <el-descriptions :column="descColumn" border class="mt16">
          <el-descriptions-item label="活动地点">{{ detail.location || '待定' }}</el-descriptions-item>
          <el-descriptions-item label="组织者">{{ detail.organizerName }}</el-descriptions-item>
          <el-descriptions-item label="活动时间">{{ fmt(detail.startTime) }} ~ {{ fmt(detail.endTime) }}</el-descriptions-item>
          <el-descriptions-item label="报名时间">{{ fmt(detail.signupStartTime) }} ~ {{ fmt(detail.signupEndTime) }}</el-descriptions-item>
          <el-descriptions-item label="已报名">{{ detail.confirmedCount }} 人</el-descriptions-item>
          <el-descriptions-item label="浏览量">{{ detail.viewCount ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="desc mt16">
          <h4>活动介绍</h4>
          <div class="desc-body">{{ detail.description || '暂无介绍' }}</div>
        </div>

        <el-alert v-if="detail.myRegistrationStatus" :title="'我的报名状态：' + detail.myRegistrationStatus"
                  :type="detail.myRegistrationStatus === '已报名' ? 'success' : detail.myRegistrationStatus === '已签到' ? 'success' : 'info'" class="mt16" show-icon />

        <div class="actions mt16">
          <el-button type="primary" size="large" :disabled="!canSignup" :loading="signing" @click="signup">
            {{ signupText }}
          </el-button>
          <el-button size="large" type="warning" plain :disabled="!canWaitlist" :loading="joining" @click="joinWaitlist">
            加入候补
          </el-button>
          <el-button v-if="detail.myRegistrationId && detail.myRegistrationStatus === '已报名'" size="large" @click="showCredential">
            我的签到凭证
          </el-button>
          <el-button v-if="detail.myRegistrationId && detail.myRegistrationStatus === '已报名'" size="large" type="danger" plain @click="cancelSignup">
            取消报名
          </el-button>
        </div>
      </div>
    </template>

    <!-- 签到凭证二维码弹窗 -->
    <el-dialog v-model="qrVisible" :title="qrData ? '电子签到凭证 - ' + qrData.activityTitle : '签到凭证'" width="420px">
      <div v-if="qrData" class="qr-box">
        <img v-if="qrDataUrl" :src="qrDataUrl" alt="签到二维码" class="qr-img" />
        <p class="text-light">请在活动开始前 30 分钟至活动结束期间，向现场工作人员出示此二维码</p>
        <p class="text-light">有效期至：{{ fmt(qrData.expireAt) }}</p>
        <p class="text-light">凭证仅限本人使用，每次打开将自动刷新防伪</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import QRCode from 'qrcode'
import { useIsMobile } from '@/composables/useIsMobile'
import { activityApi, registrationApi, waitlistApi, type ActivityDetailVO, type CheckinTokenVO } from '@/api/modules'

const route = useRoute()
const id = route.params.id as string
// 移动端描述列表降为单列（双列在 393px 下挤压换行严重）
const { isMobile } = useIsMobile()
const descColumn = computed(() => (isMobile.value ? 1 : 2))

const detail = ref<ActivityDetailVO | null>(null)
const loading = ref(false)
const signing = ref(false)
const joining = ref(false)
const qrVisible = ref(false)
const qrData = ref<CheckinTokenVO | null>(null)
const qrDataUrl = ref('')

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : ''
}
function statusType(s: number) {
  return s === 3 ? 'primary' : s === 4 ? 'success' : s === 5 ? 'info' : 'warning'
}

const canSignup = computed(() => {
  if (!detail.value) return false
  return (detail.value.status === 3 || detail.value.status === 4) && !detail.value.myRegistrationId
})
const signupText = computed(() => {
  const d = detail.value
  if (!d) return '报名'
  if (d.myRegistrationId) return d.myRegistrationStatus || '已报名'
  if (d.remaining !== null && d.remaining !== undefined && d.remaining <= 0) return '名额已满'
  return '立即报名'
})
const canWaitlist = computed(() => {
  const d = detail.value
  if (!d) return false
  return (d.status === 3 || d.status === 4) && d.remaining !== null && d.remaining !== undefined && d.remaining <= 0
})

async function load() {
  loading.value = true
  try {
    const r = await activityApi.detail(id)
    detail.value = r.data
  } finally {
    loading.value = false
  }
}

/** 报名：异步确认语义（受理 -> MQ 落库），失败分支含"名额已满(23002)"引导候补 */
async function signup() {
  signing.value = true
  try {
    const r = await registrationApi.signup(id)
    ElMessage.success(r.data.message || '报名已受理')
    setTimeout(load, 2500)
  } catch (e) {
    const err = e as { code?: number; message?: string }
    if (err.code === 23002) {
      ElMessageBox.confirm('活动名额已满，是否加入候补队列？有名额释放将自动通知您。', '名额已满', {
        confirmButtonText: '加入候补',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => joinWaitlist()).catch(() => {})
    } else {
      ElMessage.error(err.message || '报名失败')
    }
  } finally {
    signing.value = false
  }
}

async function joinWaitlist() {
  joining.value = true
  try {
    const r = await waitlistApi.join(id)
    ElMessage.success('已加入候补队列，当前排位第 ' + ((r.data.aheadCount ?? r.data.queueNo - 1) + 1) + ' 位')
    load()
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message || '加入候补失败')
  } finally {
    joining.value = false
  }
}

async function cancelSignup() {
  if (!detail.value?.myRegistrationId) return
  try {
    await ElMessageBox.confirm('确定取消报名？名额将立即释放给候补用户。', '取消报名', { type: 'warning' })
  } catch {
    return
  }
  try {
    await registrationApi.cancel(detail.value.myRegistrationId)
    ElMessage.success('已取消报名')
    load()
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message || '取消失败')
  }
}

/** 打开电子凭证：生成二维码图片 */
async function showCredential() {
  if (!detail.value?.myRegistrationId) return
  const r = await registrationApi.checkinToken(detail.value.myRegistrationId)
  qrData.value = r.data
  qrDataUrl.value = await QRCode.toDataURL(r.data.qrPayload, { width: 280, margin: 1 })
  qrVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.tags { display: flex; gap: 8px; }
.remain { text-align: right; color: #6b7280; font-size: 14px; }
.remain-num { font-size: 26px; font-weight: 700; color: #4f6ef2; }
.desc h4 { margin: 0 0 8px; }
.desc-body { white-space: pre-wrap; line-height: 1.8; color: #374151; background: #f8f9fc; padding: 16px; border-radius: 8px; }
.actions { display: flex; gap: 12px; flex-wrap: wrap; }
.actions .el-button { margin-left: 0; }
.qr-box { text-align: center; }
.qr-img { width: 280px; height: 280px; }
</style>
