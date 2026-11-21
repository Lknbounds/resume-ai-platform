import { defineStore } from 'pinia'
import { resumeApi } from '@/api/resume'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个 store 是什么】
 *  简历相关的全局状态。
 *
 *  ★ 存什么、不存什么，是有判断标准的 —— 见下面每个字段的说明。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      ResumeListView（列表页）
 *         └─ fetchList() → this.list / this.total
 *              ↓
 *      用户点某条 → router.push(/resumes/1)
 *              ↓
 *      ResumeDetailView（详情页）
 *         └─ fetchDetail(1) → this.current
 *              └─ fetchVersion(currentVersionId) → this.currentVersion
 *              ↓
 *      用户编辑后保存 → addVersion() → 内部重新 fetchDetail → 时间线刷新
 * ═══════════════════════════════════════════════════════════════════════
 */
export const useResumeStore = defineStore('resume', {

  state: () => ({
    /**
     * 简历列表（当前页）。
     *
     * ★ 放 store 而不是页面内的 ref，因为：
     *   从详情页返回列表时，如果 store 里还有数据，
     *   可以先渲染再刷新，不会闪一下空白。
     *
     *   ★ 但如果项目更在意"数据新鲜度"，页面内 ref + 每次重新拉 也合理。
     *     本项目选 store，主要是为了跨页面共享 loading 状态。
     */
    list: [],
    total: 0,

    /**
     * 当前打开的简历详情（含版本摘要列表）。
     *
     * ★ 这是详情页的核心数据源。
     */
    current: null,

    /**
     * 当前选中的版本【全文】。
     *
     * ★ 为什么单独一个字段，不放在 current 里？
     *   因为它和 current 的生命周期不同：
     *     - current 在进入详情页时加载一次
     *     - currentVersion 在每次切换版本时重新加载
     *
     *   分开之后，切换版本只更新这一个字段，current（含版本列表）不用重查。
     *   ★ 这正好利用了后端"详情不含正文"的设计 —— 切换版本只请求一份正文。
     */
    currentVersion: null,

    /** 列表加载中 */
    loading: false,

    /** 详情/版本加载中 */
    detailLoading: false
  }),

  actions: {
    /**
     * ═══════════════════════════════════════════════════════════════
     *  【拉列表】
     * ═══════════════════════════════════════════════════════════════
     */
    /**
     * ═══════════════════════════════════════════════════════════════
     *  【拉列表】支持关键词搜索
     * ═══════════════════════════════════════════════════════════════
     *
     * @param {number} page
     * @param {number} size
     * @param {string} keyword 可选。空字符串 = 不搜索
     *
     * ★ 为什么 keyword 要在这里判空后不传？
     *   传给后端空字符串时，URL 会变成 ?keyword=
     *   后端虽然也会处理（trim 后为空就走全量查询），
     *   但少传一个参数更干净，也避免 URL 里出现多余字符。
     */
    async fetchList(page = 1, size = 10, keyword = '') {
      this.loading = true
      try {
        const params = { page, size }
        if (keyword && keyword.trim()) {
          params.keyword = keyword.trim()
        }

        const data = await resumeApi.list(params)
        this.list = data.records
        this.total = data.total

        /*
         * ★ 这里 return 出去有什么用？
         *   页面可能需要知道"请求成功了吗"或者"当前页有几条"。
         *   ★ 但注意：错误是被拦截器 reject 的，
         *     所以正常能走到 return 就说明成功了。
         */
        return data
      } finally {
        this.loading = false
      }
    },

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【拉详情】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 但这里有个陷阱：fetchDetail 内部调用 fetchVersion，
     *   而 fetchVersion 有两个用途：
     *      1. 详情页初次加载（取当前版本正文）
     *      2. 用户切换版本（取另一个版本正文）
     *
     *   ★ 所以不要在 fetchDetail 里硬编码"加载当前版本"，
     *     让调用方决定。见下面的实现。
     */
    async fetchDetail(id) {
      this.detailLoading = true
      try {
        this.current = await resumeApi.detail(id)
        return this.current
      } finally {
        this.detailLoading = false
      }
    },

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【拉某个版本的全文】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 注意：这里没有再设 loading 状态。
     *   因为切换版本时用全局 loading 会让整个页面闪一下，
     *   体验不如"只在正文区域显示骨架屏"。
     *   页面组件自己用局部 ref 控制就行。
     */
    async fetchVersion(versionId) {
      this.currentVersion = await resumeApi.version(versionId)
      return this.currentVersion
    },

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【新建简历】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 创建后把返回值存进 current ——
     *   这样创建完直接跳详情页时，页面能立刻有数据渲染，
     *   不用等一次 fetchDetail（虽然详情页还是会拉一次，但不会白屏）。
     */
    async create(payload) {
      this.current = await resumeApi.create(payload)
      return this.current
    },

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【新增版本】
     * ═══════════════════════════════════════════════════════════════
     *
     * 【★ 为什么保存后要再 fetchDetail 一次？】
     *
     *   因为后端返回的只是"新版本"的信息，
     *   但页面上需要更新的东西更多：
     *      - resume.currentVersionId 变了
     *      - resume.versionCount 变了
     *      - versions 列表多了一条
     *
     *   ★ 与其在前端手动拼这四处改动（容易漏、容易和服务端不一致），
     *     不如直接重查一次。多一次请求，换来"绝对不会不一致"。
     *
     *   ★ 这是"简单正确"优于"复杂高效"的典型取舍。
     *     详情接口的数据量很小（不含正文），重查成本可以忽略。
     */
    async addVersion(id, payload) {
      const version = await resumeApi.addVersion(id, payload)

      /*
       * ★ 重新拉详情（更新版本列表、currentVersionId、versionCount）
       */
      await this.fetchDetail(id)

      /*
       * ★ 顺便把新版本的正文存进 currentVersion，
       *   这样页面不用再请求一次。
       *   （addVersion 的返回值本来就含正文）
       */
      this.currentVersion = version

      return version
    },

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【删除】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 删除后不清 current，让调用方自己决定（通常跳回列表页）。
     *   如果这里清空，详情页会瞬间变成空白（数据消失但页面还在）。
     */
    async remove(id) {
      await resumeApi.remove(id)
    },

    /**
     * 清空当前详情（离开详情页时调用，避免下次进来看到旧数据）。
     *
     * ★ 为什么需要它？
     *   用户看了简历 A，返回列表，点简历 B。
     *   如果不清理，B 的页面在数据加载完之前会先显示 A 的内容 —— 很怪。
     */
    clearCurrent() {
      this.current = null
      this.currentVersion = null
    }
  }
})
