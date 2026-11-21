import http from './http'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  简历相关的 API。8 个方法，一一对应后端的 8 个接口。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 命名和参数要跟后端对齐（清单速查）】
 *
 *      方法                  后端接口                              返回
 *      ─────────────────────────────────────────────────────────────
 *      list       GET  /resumes                    PageResult<摘要>
 *      detail     GET  /resumes/{id}               详情（含版本摘要）
 *      create     POST /resumes                    详情（含 v1）
 *      rename     PUT  /resumes/{id}               详情
 *      remove     DELETE /resumes/{id}             null
 *      addVersion POST /resumes/{id}/versions      版本详情（含正文）★
 *      versions   GET  /resumes/{id}/versions      版本摘要列表
 *      version    GET  /resumes/versions/{vid}     版本详情（含正文）★
 *
 *  ★ 注意两个"含正文"的接口：addVersion 和 version。
 *    它们返回的内容可能上万字，前端要按需调用 ——
 *    列表和详情接口都是不含正文的。
 */
export const resumeApi = {
  /**
   * 我的简历列表（分页）。
   *
   * @param {{page?: number, size?: number}} params
   * @returns {Promise<{records: Array, total: number, page: number, size: number, pages: number}>}
   *
   * ★ 每条的字段：
   *     id / title / targetPosition / currentVersionId / versionCount
   *     currentVersionPreview（前 80 字，可能为 null）
   *     updatedAt
   */
  list: (params) => http.get('/resumes', { params }),

  /**
   * 简历详情。
   *
   * @param {number} id
   * @returns {Promise<{id, title, currentVersionId, versionCount, versions: Array, createdAt, updatedAt}>}
   *
   * ★ 注意：不含正文！versions 里也只有摘要。
   *   要正文得再调 version(versionId)。
   */
  detail: (id) => http.get(`/resumes/${id}`),

  /**
   * 新建简历。
   *
   * @param {{title: string, content: string}} data
   * @returns {Promise<ResumeDetailVO>} 含 v1 的详情，前端可直接用返回的 id 跳转
   *
   * 错误：
   *     400 + 40004  正文去掉空白后不足 50 字
   */
  create: (data) => http.post('/resumes', data),

  /**
   * 改标题。
   *
   * @param {number} id
   * @param {{title: string}} data
   *
   * ★ 注意：这个接口改不了正文。改正文必须用 addVersion。
   */
  rename: (id, data) => http.put(`/resumes/${id}`, data),

  /**
   * 删除简历。
   *
   * ★★ 会级联删除：所有版本 + 所有分析报告。
   *   前端必须做二次确认（ElMessageBox.confirm）。
   */
  remove: (id) => http.delete(`/resumes/${id}`),

  /**
   * 新增版本。★ 返回含正文
   *
   * @param {number} id 简历 id
   * @param {{content: string, changeNote?: string, source?: 'PASTE'|'EDIT'|'ADOPT_COMMENT'}} data
   * @returns {Promise<VersionDetailVO>}
   *
   * 错误：
   *     400 + 40004  正文太短
   *     400 + 10000  内容与已有版本相同
   */
  addVersion: (id, data) => http.post(`/resumes/${id}/versions`, data),

  /**
   * 版本列表（摘要，不含正文）。
   *
   * ★ 本项目的前端实际用的是 detail() 返回的 versions，
   *   所以这个方法暂时没用到。
   *   留着是因为将来可能需要"只刷新版本列表"。
   */
  versions: (id) => http.get(`/resumes/${id}/versions`),

  /**
   * 取某个版本的完整正文。★ 返回含正文
   *
   * @param {number} versionId
   * @returns {Promise<VersionDetailVO>}
   *
   * ★ 什么时候调：
   *   1. 详情页打开时（取 currentVersionId 对应的正文）
   *   2. 用户点时间线上的某个版本时
   */
  version: (versionId) => http.get(`/resumes/versions/${versionId}`),

  /**
   * 上传简历文件并提取文本。
   *
   * @param {File} file PDF 或 DOCX 文件
   * @returns {Promise<{filename, text, charCount, quality:{level,message,issues}}>}
   *
   * 【★ 注意：这不是"创建简历"】
   *
   *   它只做"提取文本"，返回的 text 要填进编辑框让用户预览。
   *   用户确认后再调 create() 创建简历。
   *
   *   为什么要分两步？
   *     PDF 解析出错是常态（扫描件/双栏/字体）。
   *     直接创建的话，用户会拿着一份"读错了的简历"得到假分数。
   *     让他先看一眼、顺手改改，再创建 —— 多一次请求换可靠性。
   *
   * ★ FormData 是浏览器原生的表单数据格式。
   *   为什么不用 JSON 传 base64？
   *     1. base64 会让体积膨胀 33%
   *     2. 后端要手动解码，多一步
   *     3. multipart 是文件上传的标准方式，Spring 原生支持
   *
   * ★ 为什么手动设 Content-Type？
   *   不设的话 axios 会自动设成 application/json，文件传不过去。
   *   ★ 但也不能写死 'multipart/form-data' ——
   *     需要浏览器自动加上 boundary 分隔符。
   *     所以传 undefined，让 axios 自己根据 FormData 判断。
   */
  extract: (file) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/resumes/extract', form, {
      headers: { 'Content-Type': undefined },
      // 文件解析可能慢一点，单独给 30 秒
      timeout: 30000
    })
  },

  /**
   * 版本对比 —— 步骤 6 实现。
   *
   * ★ 现在先占位，避免步骤 6 时忘了加。
   *   后端接口也还没写（GET /resumes/{id}/compare）。
   */
  compare: (id, params) => http.get(`/resumes/${id}/compare`, { params }),
  /**
   * 上传某个版本的 PDF 原件。
   * 原件按版本保存，用于电脑端三栏工作台预览。
   */
  uploadOriginal: (versionId, file) => {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/resumes/versions/${versionId}/original`, form, {
      headers: { 'Content-Type': undefined },
      timeout: 30000
    })
  },

  /**
   * 获取某个版本的 PDF 原件二进制内容。
   */
  originalBlob: (versionId) => http.get(`/resumes/versions/${versionId}/original`, {
    responseType: 'blob',
    timeout: 30000
  })
}
