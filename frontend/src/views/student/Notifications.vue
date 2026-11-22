<template>
  <div class="page-container">
    <div class="flex-between mb16">
      <h2 class="page-title">消息通知</h2>
      <el-button @click="markAll">全部已读</el-button>
    </div>
    <div class="card">
      <el-tabs v-model="tab" @tab-change="load">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane label="未读" name="unread" />
      </el-tabs>
      <div v-loading="loading">
        <div v-for="n in list" :key="n.id" class="notify-item" :class="{ unread: n.isRead === 0 }" @click="markRead(n)">
          <div class="notify-head">
            <el-badge is-dot :hidden="n.isRead === 1">
              <span class="notify-title">{{ typeIcon(n.type) }} {{ n.title }}</span>
            </el-badge>
            <span class="text-light">{{ fmt(n.createdAt) }}</span>
          </div>
          <p class="notify-content">{{ n.content }}</p>
        </div>
        <el-empty v-if="!loading && list.length === 0" description="暂无消息" />
      </div>
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
import { notifyApi, type NotifyVO } from '@/api/modules'

const list = ref<NotifyVO[]>([])
const tab = ref('all')
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)

function fmt(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : ''
}
function typeIcon(type: number) {
  const map: Record<number, string> = { 0: '✅', 1: '⚠️', 2: '🗑️', 3: '⏳', 4: '🎉', 5: '⌛', 6: '📍', 7: '📢', 8: '🚫', 9: '✅', 10: '❌' }
  return map[type] ?? '🔔'
}

async function load() {
  loading.value = true
  try {
    const r = await notifyApi.list({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      unreadOnly: tab.value === 'unread' ? true : undefined
    })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

async function markRead(n: NotifyVO) {
  if (n.isRead === 0) {
    await notifyApi.markRead(n.id)
    n.isRead = 1
  }
}

async function markAll() {
  await notifyApi.markAllRead()
  ElMessage.success('已全部标记已读')
  load()
}

onMounted(load)
</script>

<style scoped>
.notify-item { padding: 14px 16px; border-bottom: 1px solid #f0f2f7; cursor: pointer; border-radius: 8px; }
.notify-item:hover { background: #f8f9fc; }
.notify-item.unread { background: #f5f8ff; }
.notify-head { display: flex; justify-content: space-between; align-items: center; }
.notify-title { font-weight: 600; font-size: 14px; }
.notify-content { margin: 8px 0 0; color: #6b7280; font-size: 13px; line-height: 1.6; }
.pager { display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
