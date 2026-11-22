<template>
  <div class="page-container">
    <h2 class="page-title">系统仪表盘</h2>
    <template v-if="d">
      <div class="metric-grid mb16">
        <div class="card metric"><div class="metric-num">{{ d.totalUsers }}</div><div class="text-light">注册用户</div></div>
        <div class="card metric"><div class="metric-num">{{ d.totalActivities }}</div><div class="text-light">活动总数</div></div>
        <div class="card metric"><div class="metric-num warn">{{ d.pendingReviewCount }}</div><div class="text-light">待审核活动</div></div>
        <div class="card metric"><div class="metric-num">{{ d.totalRegistrations }}</div><div class="text-light">有效报名总数</div></div>
        <div class="card metric"><div class="metric-num success">{{ d.todayRegistrations }}</div><div class="text-light">今日新增报名</div></div>
        <div class="card metric"><div class="metric-num">{{ d.totalCheckins }}</div><div class="text-light">签到总数</div></div>
      </div>
      <div class="health-grid mb16">
        <div class="card metric"><div class="metric-num warn">{{ d.pendingCompensationTasks }}</div><div class="text-light">待处理补偿任务</div></div>
        <div class="card metric"><div class="metric-num danger">{{ d.deadCompensationTasks }}</div><div class="text-light">死信补偿任务</div></div>
        <div class="card metric"><div class="metric-num danger">{{ d.failedMessageCount }}</div><div class="text-light">投递失败消息</div></div>
      </div>
      <div class="charts">
        <div class="card">
          <h3>近 14 天报名趋势</h3>
          <div ref="trendChart" class="chart"></div>
        </div>
        <div class="card">
          <h3>活动分类分布</h3>
          <div ref="pieChart" class="chart"></div>
        </div>
      </div>
    </template>
    <div v-else v-loading="true" class="card" style="height: 400px"></div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { adminApi, type AdminDashboardVO } from '@/api/modules'

const d = ref<AdminDashboardVO | null>(null)
const trendChart = ref<HTMLElement>()
const pieChart = ref<HTMLElement>()

onMounted(async () => {
  const r = await adminApi.dashboard()
  d.value = r.data
  if (trendChart.value) {
    echarts.init(trendChart.value).setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 30, bottom: 30 },
      xAxis: { type: 'category', data: r.data.registrationTrend.map((x) => x.date.slice(5)) },
      yAxis: { type: 'value', minInterval: 1 },
      series: [{ type: 'line', smooth: true, areaStyle: { opacity: 0.15 }, data: r.data.registrationTrend.map((x) => x.count), itemStyle: { color: '#4f6ef2' } }]
    })
  }
  if (pieChart.value) {
    echarts.init(pieChart.value).setOption({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [{
        type: 'pie', radius: ['40%', '65%'],
        data: r.data.categoryDistribution.map((c) => ({ name: c.categoryName, value: c.count }))
      }]
    })
  }
})
</script>

<style scoped>
.metric-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.health-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.metric { text-align: center; padding: 16px; }
.metric-num { font-size: 26px; font-weight: 700; color: #4f6ef2; }
.metric-num.success { color: #22a06b; }
.metric-num.warn { color: #f59e0b; }
.metric-num.danger { color: #ef4444; }
.charts { display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px; }
.chart { height: 320px; }
@media (max-width: 1000px) {
  .metric-grid { grid-template-columns: repeat(3, 1fr); }
  .charts { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .metric-grid, .health-grid { grid-template-columns: repeat(2, 1fr); }
  .metric-num { font-size: 20px; }
}
</style>
