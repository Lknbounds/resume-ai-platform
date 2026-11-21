import http from './http'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  分析相关 API。
 * ═══════════════════════════════════════════════════════════════════════
 */
export const analysisApi = {
  /**
   * 发起分析。★ 同步返回完整报告。
   *
   * @param {{resumeVersionId:number, jdId:number, forceRefresh?:boolean}} data
   * @returns {Promise<AnalysisReportVO>}
   *
   * ★ 现在是同步的（规则解析几十毫秒）。
   *   接 AI 之后会变成 202 + 轮询/SSE，但前端调用方式不变 ——
   *   只是 create 返回的是 reportId 而不是完整报告。
   */
  create: (data) => http.post('/analyses', data),

  /** 报告详情 */
  detail: (id) => http.get(`/analyses/${id}`),

  /**
   * 分析历史。
   *
   * @param {{resumeVersionId?:number, page?:number, size?:number}} params
   */
  list: (params) => http.get('/analyses', { params }),

  /** 删除报告 */
  remove: (id) => http.delete(`/analyses/${id}`),

  /**
   * 导出 PDF 报告。
   *
   * 【★ 为什么不用 http（axios 实例），而是用原生 fetch？】
   *
   *  我们的 http 拦截器会把响应当 JSON 处理：
   *      if (body.code === 0) return body.data
   *  但 PDF 接口返回的是【二进制流】，没有 code 字段。
   *  经过拦截器会被当成"失败"，或者直接把 Blob 当 JSON 解析报错。
   *
   *  ★ 所以下载文件类接口必须绕开拦截器，用原生 fetch。
   *    代价是要自己处理鉴权头（手动加 Authorization）。
   *
   * 【★ 这个函数返回 Blob，不直接触发下载】
   *   让调用方决定什么时候下载 —— 便于显示 loading、处理错误。
   */
  exportPdf: async (id, filename) => {
    const token = localStorage.getItem('accessToken')

    const res = await fetch(`/api/analyses/${id}/export/pdf`, {
      headers: { Authorization: `Bearer ${token}` }
    })

    if (!res.ok) {
      /*
       * ★ 后端出错时返回的是 JSON（GlobalExceptionHandler 的格式），
       *   这里尝试解析出 message 给用户看。
       */
      let msg = '导出失败'
      try {
        const body = await res.json()
        msg = body.message || msg
      } catch {
        // 不是 JSON，用默认提示
      }
      throw new Error(msg)
    }

    const blob = await res.blob()

    /*
     * ★ 触发浏览器下载的标准三步：
     *   1. 创建一个指向 Blob 的临时 URL
     *   2. 造一个 <a download> 点一下
     *   3. 释放 URL（不然内存泄漏）
     */
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename || `简历分析报告-${id}.pdf`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)

    return blob
  }
}
