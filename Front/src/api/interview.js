import http from './http'

/**
 * 面试预演题 API。
 *
 * 当前接口固定返回 5 道本地题目，每道题包含参考答案和辅助信息。
 */
export const interviewApi = {
  questions: () => http.get('/interview/questions')
}