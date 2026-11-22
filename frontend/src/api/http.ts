/**
 * Axios 统一封装：Token 注入、统一响应解包、401 跳转登录、错误提示、TraceId 透传。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'

export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  traceId?: string
}

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('campusflow_token')
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResult
    // 后端统一 code=0 成功；业务错误码交给调用方或统一提示
    if (body.code !== 0) {
      if (body.code === 40101) {
        localStorage.removeItem('campusflow_token')
        localStorage.removeItem('campusflow_user')
        if (!location.pathname.startsWith('/login')) {
          location.href = '/login'
        }
      }
      // 部分场景调用方需要拿到错误码做分支（如名额已满引导候补），reject 携带完整 body
      return Promise.reject(body)
    }
    // 解包统一响应体：调用方拿到 ApiResult（类型上伪装成 AxiosResponse 以适配 axios 拦截器签名）
    normalizePage(body.data)
    return body as unknown as import('axios').AxiosResponse
  },
  (error) => {
    // HTTP 层错误（超时/网络/500）
    const body = error?.response?.data
    if (body && typeof body.code === 'number') {
      if (body.code === 40101) {
        localStorage.removeItem('campusflow_token')
        location.href = '/login'
      }
      return Promise.reject(body)
    }
    ElMessage.error(error.message || '网络异常，请稍后重试')
    return Promise.reject({ code: -1, message: error.message })
  }
)

/**
 * 分页字段数值标准化：后端为规避 JS Long 精度问题把所有 Long 序列化为字符串，
 * 分页 total/pageNum/pageSize 是小整数，统一转回 number 供 el-pagination 等组件使用；
 * 业务 ID（雪花 19 位）保持字符串，绝不转 number（会丢精度）。
 */
function normalizePage(data: unknown) {
  if (data && typeof data === 'object' && 'records' in (data as object) && 'total' in (data as object)) {
    const p = data as { total: unknown; pageNum: unknown; pageSize: unknown }
    p.total = Number(p.total)
    p.pageNum = Number(p.pageNum)
    p.pageSize = Number(p.pageSize)
  }
}

export default http
