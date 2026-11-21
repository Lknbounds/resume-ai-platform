import { defineStore } from 'pinia'
import { metaApi } from '@/api/meta'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  功能开关的全局状态。应用启动时拉一次，全应用共享。
 *
 *  它决定了界面上"哪些东西显示、哪些不显示"。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      router 守卫（首次进入任何页面时）
 *         └─ feature.load()          ← 只有第一次真正发请求
 *              └─ metaApi.features() → 后端读 AiProperties
 *              ↓
 *      存进 state
 *              ↓
 *      【读】DefaultLayout    → interviewModuleEnabled 决定导航栏显不显示"面试预演"
 *      【读】router 守卫      → 直接输 URL 时拦不拦
 *      【读】报告页           → aiEnabled 决定显示"AI 优化"按钮 / 降级提示
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么把功能开关做成全局 store，而不是每个页面自己请求？】
 *
 *  如果不做 store，每个需要用的组件各调一次：
 *      - 导航栏调一次
 *      - 路由守卫调一次
 *      - 报告页调一次
 *      - 面试页调一次
 *      → 4 次重复请求，而且可能存在"某个组件拿到 true、另一个拿到 false"的不一致
 *
 *  ★ 做成 store 之后：
 *      - 只请求一次（loaded 标记控制）
 *      - 所有地方读同一份数据，永远一致
 *      - 后端改了配置，刷新页面就生效
 *
 *  ★ 这是"跨页面共享状态"的典型场景，
 *    也是判断"什么该放 Pinia"的标准：
 *    多个页面都要用、且要保持一致的，才放 store。
 *    只有当前页面用的（比如表单内容），放组件的 ref 就行。
 */
export const useFeatureStore = defineStore('feature', {

  state: () => ({
    /**
     * 是否已经拉取过。
     *
     * ★ 这个标记是"只请求一次"的关键。
     *   没有它的话，每次路由跳转都会重新请求。
     */
    loaded: false,

    /**
     * AI 总开关。
     *
     * ★ 默认值为什么是 false?
     *   保守默认。在请求返回前，界面按"没有 AI"渲染 ——
     *   这样不会出现"先是显示了 AI 按钮，300ms 后又消失"的闪烁。
     */
    aiEnabled: false,

    /** 分功能开关。初始空对象，请求后填充 */
    aiFeatures: {},

    /**
     * 面试模块是否整体可见。
     *
     * ★ 这是"整个模块"级别的开关，不是单个能力。
     *   用途：
     *      1. 导航栏 v-if
     *      2. 路由守卫拦截
     */
    interviewModuleEnabled: false,

    /**
     * 参考公开信息模式。
     *
     * ★ 它不依赖 AI（本地面经数据集是静态文件），
     *   所以默认 true。
     */
    externalInfoEnabled: true,

    /**
     * 评分算法版本。用于在界面上标注"当前算法 v1"，
     * 也让"不同版本的分数不可比"这件事对用户可见。
     */
    scoringVersion: 'v1'
  }),

  actions: {
    /**
     * ═══════════════════════════════════════════════════════════════
     *  【拉取功能开关】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么开头判断 if (this.loaded) return？
     *   这就是"只请求一次"的实现。
     *   第一次调用：loaded=false → 请求 → 置 true
     *   后续调用：直接返回，不发请求
     *
     * ★ 为什么不是 await 内部再加锁？
     *   理论上两个并发调用可能都通过 loaded 检查、都发请求。
     *   但实际上只有路由守卫调它，路由守卫是串行的（一次只导航一个页面），
     *   所以不会并发。这里不需要额外的锁。
     *
     *   ★ 如果要在组件里也调，建议只调 router 那一次就够了。
     */
    async load() {
      if (this.loaded) return

      /*
       * ★ 请求失败会怎样？
       *   这里没写 try-catch，异常会抛给调用方。
       *   路由守卫里会 catch 掉（因为"后端没起来"不该卡死路由）。
       *   见 router/index.js：
       *       try { await feature.load() } catch { }
       *
       * ★ 此时 loaded 仍然是 false ——
       *   下次导航会再试一次。这是想要的行为：
       *   如果后端临时挂了，恢复了之后功能开关能自动生效。
       */
      const data = await metaApi.features()

      this.aiEnabled = data.aiEnabled
      this.aiFeatures = data.aiFeatures || {}
      this.interviewModuleEnabled = data.interviewModuleEnabled
      this.externalInfoEnabled = data.externalInfoEnabled
      this.scoringVersion = data.scoringVersion
      this.loaded = true
    },

    /**
     * 强制重新拉取（忽略 loaded 标记）。
     *
     * ★ 什么时候用？
     *   比如开发时改了后端的 AI_ENABLED 并重启，
     *   前端不刷新页面也能看到变化。
     *   （本项目没做"设置页手动刷新"，但留着这个方法方便调试）
     */
    async reload() {
      this.loaded = false
      await this.load()
    }
  }
})
