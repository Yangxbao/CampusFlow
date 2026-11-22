<template>
  <div class="page-container">
    <h2 class="page-title">{{ isEdit ? '编辑活动' : '创建活动' }}</h2>
    <div class="card" v-loading="loading">
      <el-form :model="form" label-width="120px" style="max-width: 720px">
        <el-form-item label="活动标题" required>
          <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="请输入活动标题" />
        </el-form-item>
        <el-form-item label="分类" required>
          <el-select v-model="form.category" placeholder="选择分类">
            <el-option v-for="c in categories" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="活动地点">
          <el-input v-model="form.location" maxlength="128" />
        </el-form-item>
        <el-form-item label="活动时间" required>
          <el-date-picker v-model="range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
                          start-placeholder="开始时间" end-placeholder="结束时间" />
        </el-form-item>
        <el-form-item label="报名时间" required>
          <el-date-picker v-model="signupRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
                          start-placeholder="报名开始" end-placeholder="报名截止" />
        </el-form-item>
        <el-form-item label="名额上限" required>
          <el-input-number v-model="form.capacity" :min="1" :max="100000" />
        </el-form-item>
        <el-form-item label="活动介绍">
          <el-input v-model="form.description" type="textarea" :rows="6" maxlength="20000" show-word-limit />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button @click="$router.back()">返回</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { organizerApi } from '@/api/modules'

const route = useRoute()
const router = useRouter()
const id = route.params.id as string | undefined
const isEdit = computed(() => !!id)

const categories = [
  { value: 0, label: '学术讲座' },
  { value: 1, label: '技术交流' },
  { value: 2, label: '创新创业比赛' },
  { value: 3, label: '就业指导' },
  { value: 4, label: '社团活动' },
  { value: 5, label: '志愿服务' },
  { value: 6, label: '校园招聘宣讲' }
]

const form = reactive({
  title: '',
  category: 0 as number,
  location: '',
  capacity: 100,
  description: ''
})
const range = ref<[string, string] | null>(null)
const signupRange = ref<[string, string] | null>(null)
const saving = ref(false)
const loading = ref(false)

async function load() {
  if (!id) return
  loading.value = true
  try {
    // 详情接口返回（含编辑所需字段）：用组织者列表接口按 id 查找
    const r = await organizerApi.myActivities({ pageNum: 1, pageSize: 100 })
    const act = r.data.records.find((a) => String(a.id) === id)
    if (act) {
      form.title = act.title
      form.category = act.category
      form.location = act.location || ''
      form.capacity = act.capacity
      range.value = [act.startTime, act.endTime]
      signupRange.value = [act.signupStartTime, act.signupEndTime]
    }
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.title || !range.value || !signupRange.value) {
    ElMessage.warning('请完整填写必填项')
    return
  }
  const body = {
    title: form.title,
    category: form.category,
    location: form.location,
    description: form.description,
    capacity: form.capacity,
    startTime: range.value[0],
    endTime: range.value[1],
    signupStartTime: signupRange.value[0],
    signupEndTime: signupRange.value[1]
  }
  saving.value = true
  try {
    if (isEdit.value && id) {
      await organizerApi.update(Number(id), body)
      ElMessage.success('保存成功')
    } else {
      await organizerApi.create(body)
      ElMessage.success('创建成功，可在工作台提交审核')
    }
    router.push('/organizer/workbench')
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
