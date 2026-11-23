import http from './http'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  JD（目标岗位）相关 API。
 * ═══════════════════════════════════════════════════════════════════════
 */
export const jdApi = {
  /**
   * 我的 JD 列表（分页）。
   *
   * @param {{keyword?: string, page?: number, size?: number}} params
   */
  list: (params) => http.get('/jds', { params: params || {} }),

  /** JD 详情（含解析结果） */
  detail: (id) => http.get(`/jds/${id}`),

  /**
   * 保存 JD。★ 后端会立即用规则解析出技能要求。
   *
   * @param {{title:string, company?:string, content:string}} data
   * @returns {Promise<JdVO>} 含 parsed（技能列表、年限、学历）
   */
  create: (data) => http.post('/jds', data),

  /** 更新 JD。内容变了会重新解析 */
  update: (id, data) => http.put(`/jds/${id}`, data),

  /** 删除 JD（级联删除相关分析报告） */
  remove: (id) => http.delete(`/jds/${id}`),

  /**
   * 从 Markdown/TXT 文件导入 JD。
   *
   * 不手动设置 Content-Type：浏览器会自动生成带 boundary 的
   * multipart/form-data，手写 application/json 或缺少 boundary 都会失败。
   */
  importFile: (file) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/jds/import', form, { timeout: 30000 })
  }
}
