<template>
  <div class="page-container">
    <div class="flex-between mb16">
      <h2 class="page-title">活动统计</h2>
      <el-button @click="$router.back()">返回</el-button>
    </div>
    <template v-if="stats">
      <!-- 指标卡 -->
      <div class="metric-grid mb16">
        <div class="card metric"><div class="metric-num">{{ stats.confirmedCount }}/{{ stats.capacity }}</div><div class="text-light">已报名/名额</div></div>
        <div class="card metric"><div class="metric-num success">{{ stats.checkedInCount }}</div><div class="text-light">已签到</div></div>
        <div class="card metric"><div class="metric-num warn">{{ stats.waitlistWaitingCount }}</div><div class="text-light">候补排队</div></div>
        <div class="card metric"><div class="metric-num danger">{{ stats.cancelledCount }}</div><div class="text-light">已取消</div></div>
        <div class="card metric"><div class="metric-num">{{ stats.remainingStock ?? '-' }}</div><div class="text-light">Redis 剩余名额</div></div>
        <div class="card metric"><div class="metric-num">{{ stats.viewCount ?? '-' }}</div><div class="text-light">浏览量</div></div>
      </div>
      <!-- ECharts 图表 -->
      <div class="card mb16">
        <h3>近 14 天报名趋势</h3>
        <div ref="regChart" class="chart"></div>
      </div>
      <div class="card">
        <h3>近 14 天签到趋势</h3>
        <div ref="checkinChart" class="chart"></div>
      </div>
    </template>
    <div v-else v-loading="true" class="card" style="height: 300px"></div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { organizerApi, type ActivityStatsVO } from '@/api/modules'

const route = useRoute()
const id = route.params.id as string
const stats = ref<ActivityStatsVO | null>(null)
const regChart = ref<HTMLElement>()
const checkinChart = ref<HTMLElement>()

function draw(el: HTMLElement | undefined, title: string, data: { date: string; count: number }[]) {
  if (!el) return
  const chart = echarts.init(el)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: data.map((d) => d.date.slice(5)) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ name: title, type: 'line', smooth: true, areaStyle: { opacity: 0.15 }, data: data.map((d) => d.count), itemStyle: { color: '#4f6ef2' } }]
  })
}

onMounted(async () => {
  const r = await organizerApi.stats(Number(id))
  stats.value = r.data
  draw(regChart.value, '报名', r.data.dailyRegistrations)
  draw(checkinChart.value, '签到', r.data.dailyCheckins)
})
</script>

<style scoped>
.metric-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.metric { text-align: center; padding: 16px; }
.metric-num { font-size: 24px; font-weight: 700; color: #4f6ef2; }
.metric-num.success { color: #22a06b; }
.metric-num.warn { color: #f59e0b; }
.metric-num.danger { color: #ef4444; }
.chart { height: 300px; }
@media (max-width: 1000px) { .metric-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 600px) { .metric-grid { grid-template-columns: repeat(2, 1fr); } .metric-num { font-size: 20px; } .chart { height: 240px; } }
</style>
