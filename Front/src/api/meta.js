import http from './http'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  元信息 API。目前只有一个：功能开关。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么这个接口不需要登录？】
 *  它返回的是"这个部署实例的能力"，不是用户数据。
 *  后端 SecurityConfig 里放行了 /api/meta/**。
 *
 *  ★ 前端在登录页就可能需要它（比如决定登录页上要不要显示某些入口）。
 */
export const metaApi = {
  /**
   * 查功能开关。
   *
   * @returns {Promise<{
   *   aiEnabled: boolean,
   *   aiFeatures: {jdParse, resumeParse, suggestions, rewrite, interview, privacyAssist},
   *   interviewModuleEnabled: boolean,
   *   externalInfoEnabled: boolean,
   *   scoringVersion: string
   * }>}
   *
   * 【★ 这个接口的三个用途】
   *
   *  1. 隐藏"面试预演"入口
   *     feature.interviewModuleEnabled === false 时：
   *       - 导航栏不显示这一项
   *       - 直接输 URL /interview 会被路由守卫拦回首页
   *
   *  2. 隐藏"AI 优化"按钮
   *     feature.aiFeatures.rewrite === false 时，报告页不显示该按钮
   *
   *  3. 显示降级提示
   *     feature.aiEnabled === false 时，报告页顶部可能提示
   *     "当前为规则模式"
   *
   * ★ 调用时机：应用启动时调一次，存进 useFeatureStore。
   *   见 stores/feature.js 和 router/index.js 的守卫。
   */
  features: () => http.get('/meta/features')
}
