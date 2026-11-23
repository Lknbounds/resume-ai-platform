import http from './http'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  社区 API：帖子 + 评论 + 投票。
 * ═══════════════════════════════════════════════════════════════════════
 */
export const postApi = {
  /**
   * 发帖。
   *
   * @param {{reportId, title, content?, resumeContent, isAnonymous?}} data
   * @returns {Promise<PostVO>}
   *
   * ★ 命中个人信息时会被拦截器 reject，错误体是：
   *      {code: 43002, message: "...", data: {hits: [...]}}
   *   页面要 catch 它并弹出高亮对话框。
   */
  publish: (data) => http.post('/posts', data),

  /**
   * 列表。
   *
   * @param {{position?, expMin?, expMax?, keyword?, sort?, page?, size?}} params
   */
  list: (params) => http.get('/posts', { params }),

  detail: (id) => http.get(`/posts/${id}`),

  remove: (id) => http.delete(`/posts/${id}`),

  /** 我的帖子 */
  mine: (params) => http.get('/posts/mine', { params }),

  /** 投票（UP / DOWN） */
  vote: (id, voteType) => http.post(`/posts/${id}/votes`, { voteType })
}

export const commentApi = {
  /** 评论列表（两级树） */
  list: (postId, params) => http.get(`/posts/${postId}/comments`, { params }),

  /** 发表评论或回复 */
  create: (postId, data) => http.post(`/posts/${postId}/comments`, data),

  remove: (id) => http.delete(`/comments/${id}`),

  /** 评论投票（UP / USEFUL） */
  vote: (id, voteType) => http.post(`/comments/${id}/votes`, { voteType }),

  /** 采纳评论；传 updatedResumeContent 时同时生成简历新版本 */
  adopt: (id, data = {}) => http.post(`/comments/${id}/adopt`, data)
}

export const privacyApi = {
  /**
   * 发布前预检。
   *
   * @returns {Promise<{passed, ruleVersion, hits}>}
   * ★ 这个接口【不落库】，可以放心反复调。
   */
  precheck: (data) => http.post('/privacy/precheck', data)
}
