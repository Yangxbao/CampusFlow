<template>
  <div class="page-container">
    <!-- 推荐位 -->
    <div class="card hero mb16">
      <div>
        <h2 class="hero-title">发现校园精彩活动</h2>
        <p class="text-light">讲座 · 比赛 · 社团 · 志愿服务 · 招聘宣讲</p>
      </div>
      <el-input v-model="keyword" placeholder="搜索活动标题..." size="large" class="search" clearable @keyup.enter="load">
        <template #append>
          <el-button :icon="Search" @click="load" />
        </template>
      </el-input>
    </div>

    <!-- 分类（移动端横向滚动，不换行溢出） -->
    <div class="card mb16 cat-bar">
      <el-radio-group v-model="category" @change="load">
        <el-radio-button :value="-1">全部</el-radio-button>
        <el-radio-button v-for="c in categories" :key="c.value" :value="c.value">{{ c.label }}</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 列表 -->
    <div v-loading="loading" class="grid">
      <div v-for="a in list" :key="a.id" class="card activity-card" @click="$router.push('/activities/' + a.id)">
        <div class="ac-top">
          <el-tag size="small" effect="plain">{{ a.categoryName }}</el-tag>
          <el-tag size="small" :type="statusType(a.status)">{{ a.statusDesc }}</el-tag>
        </div>
        <h3 class="ac-title">{{ a.title }}</h3>
        <p class="ac-line"><el-icon><Location /></el-icon>{{ a.location || '待定' }}</p>
        <p class="ac-line"><el-icon><Calendar /></el-icon>{{ fmt(a.startTime) }}</p>
        <div class="ac-bottom">
          <span class="text-light">{{ a.organizerName }}</span>
          <span class="quota"><b>{{ a.confirmedCount }}</b>/{{ a.capacity }} 人</span>
        </div>
        <el-progress :percentage="pct(a)" :color="pct(a) >= 100 ? '#ef4444' : '#4f6ef2'" :stroke-width="6" :show-text="false" />
      </div>
      <el-empty v-if="!loading && list.length === 0" description="暂无活动" class="empty" />
    </div>

    <div class="pager">
      <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { activityApi, type ActivityVO } from '@/api/modules'

const categories = [
  { value: 0, label: '学术讲座' },
  { value: 1, label: '技术交流' },
  { value: 2, label: '比赛' },
  { value: 3, label: '就业指导' },
  { value: 4, label: '社团活动' },
  { value: 5, label: '志愿服务' },
  { value: 6, label: '招聘宣讲' }
]

const list = ref<ActivityVO[]>([])
const keyword = ref('')
const category = ref(-1)
const pageNum = ref(1)
const pageSize = ref(12)
const total = ref(0)
const loading = ref(false)

function fmt(t?: string) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}
function statusType(s: number) {
  return s === 3 ? 'primary' : s === 4 ? 'success' : 'info'
}
function pct(a: ActivityVO) {
  return a.capacity > 0 ? Math.round((a.confirmedCount / a.capacity) * 100) : 0
}

async function load() {
  loading.value = true
  try {
    const r = await activityApi.list({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      category: category.value >= 0 ? category.value : undefined,
      keyword: keyword.value || undefined
    })
    list.value = r.data.records
    total.value = r.data.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(120deg, #4f6ef2 0%, #6a8bff 100%);
  color: #ffffff;
}
.hero-title { margin: 0 0 4px; font-size: 22px; color: #ffffff; }
.hero :deep(.text-light) { color: rgba(255, 255, 255, 0.8); }
.search { width: 360px; }
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.activity-card { cursor: pointer; transition: transform 0.15s ease, box-shadow 0.15s ease; }
.activity-card:hover { transform: translateY(-3px); box-shadow: 0 6px 18px rgba(79, 110, 242, 0.15); }
.ac-top { display: flex; justify-content: space-between; }
.ac-title { margin: 10px 0 8px; font-size: 16px; line-height: 1.4; }
.ac-line { display: flex; align-items: center; gap: 6px; color: #6b7280; font-size: 13px; margin: 6px 0; }
.ac-bottom { display: flex; justify-content: space-between; align-items: center; margin: 10px 0 8px; }
.quota b { color: #4f6ef2; }
.empty { grid-column: 1 / -1; }
.pager { display: flex; justify-content: center; padding: 20px 0; }
@media (max-width: 900px) {
  .grid { grid-template-columns: 1fr; }
  .hero { flex-direction: column; align-items: flex-start; gap: 12px; padding: 16px; }
  .hero-title { font-size: 18px; }
  .search { width: 100%; }
}
/* 分类条：移动端横向滚动，桌面端正常换行展示 */
.cat-bar { overflow-x: auto; }
.cat-bar :deep(.el-radio-group) { flex-wrap: nowrap; white-space: nowrap; }
@media (min-width: 769px) {
  .cat-bar :deep(.el-radio-group) { flex-wrap: wrap; }
}
</style>
