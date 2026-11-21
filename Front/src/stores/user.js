import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { userApi } from '@/api/user'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  用户状态的"全局仓库"。保存登录态和用户资料，全应用共享。
 *
 *  Pinia 的 store 就像一个全局响应式对象：
 *      任何组件改了它，所有用到它的地方自动更新。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：谁读谁写】
 *
 *      【写】LoginView  → user.login(form)
 *              ├─ 调 authApi.login → 拿到两个 token
 *              ├─ setTokens(...)   → 写 state + localStorage
 *              └─ profile = data.user
 *              ↓
 *      【读】http.js 请求拦截器 → user.accessToken → 加到请求头
 *      【读】DefaultLayout      → user.displayName → 右上角昵称
 *      【读】ProfileView        → user.profile → 表单初值
 *              ↓
 *      【写】UserProfile 保存 → user.profile = 新的 UserVO
 *              ↓
 *      所有显示昵称的地方自动更新（因为响应式）
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么 token 要同时存在 state 和 localStorage？】
 *
 *  这是本项目一个重要的设计，两个地方各有用处：
 *
 *  ┌────────────────┬──────────────────────────────────────────┐
 *  │ state（内存）   │ 响应式，改了界面立刻更新；                │
 *  │                │ http.js 从这里读 token 加到请求头         │
 *  ├────────────────┼──────────────────────────────────────────┤
 *  │ localStorage   │ 刷新页面后还在（内存会被清空）；           │
 *  │                │ state 初始化时从这里读回来                │
 *  └────────────────┴──────────────────────────────────────────┘
 *
 *  ★ 只存 state：刷新页面就掉线，用户得重新登录
 *  ★ 只存 localStorage：不是响应式的，改了 token 界面不更新
 *  ★ 两个都存：刷新不掉线 + 实时响应
 *
 *  【★★ 为什么用 localStorage 而不是 Cookie？】
 *
 *  三个方案对比：
 *
 *  ⚠️ 内存（只在 Pinia 里）
 *      最安全（关页面就没了），但刷新就掉线 —— 用户体验不可接受
 *
 *  ⚠️ httpOnly Cookie
 *      最安全（JS 读不到，防 XSS 窃取），但需要：
 *        - 后端 Set-Cookie + SameSite 配置
 *      前端 withCredentials
 *      重新启用 CSRF 防护
 *      和"前后端完全分离 + Bearer token"的架构不太契合
 *
 *  ✅ localStorage（本项目）
 *      刷新不掉线、前端完全掌控、和 Bearer 头天然搭配
 *      ★ 代价：XSS 能读到。
 *
 *  ★ 所以对应的风险控制是：【前端绝不用 v-html 渲染用户内容】。
 *    简历、评论、帖子全部当纯文本显示，这样即使有人往简历里写
 *    <script>...</script>，也只会被当成文字显示，不会执行。
 *
 *    这是一组配套的取舍，面试时要说清楚"我知道风险，也知道怎么控制"。
 */
export const useUserStore = defineStore('user', {

  /**
   * ═══════════════════════════════════════════════════════════════
   *  【state】初始状态
   * ═══════════════════════════════════════════════════════════════
   *
   * ★ 为什么 state 是个函数而不是对象？
   *   Pinia 规定必须是函数，这样每次创建 store 时都会执行一次，
   *   得到一个全新的对象。如果直接写对象，多个地方会共享同一个引用，
   *   改一处会影响另一处。
   *
   * ★ 为什么 token 初始值要从 localStorage 读？
   *   因为刷新页面时内存被清空了，但 localStorage 还在。
   *   从它读回来，就能实现"刷新不掉线"。
   */
  state: () => ({
    accessToken: localStorage.getItem('accessToken') || '',
    refreshToken: localStorage.getItem('refreshToken') || '',

    /**
     * 用户资料。初始为 null，登录后或调 fetchProfile 后有值。
     *
     * ★ 为什么不也存 localStorage？
     *   因为资料会变（改昵称），存了容易和服务器不一致 ——
     *   用户改了昵称但 localStorage 还是旧的，就会显示错。
     *   所以每次刷新页面都重新拉一次（见 router 守卫），
     *   保证显示的一定是最新的。
     */
    profile: null
  }),

  /**
   * ═══════════════════════════════════════════════════════════════
   *  【getters】派生状态（由 state 算出来的）
   * ═══════════════════════════════════════════════════════════════
   *
   * ★ 和 methods 的区别：
   *   getters 是"用 state 算出来的值"，会自动缓存（依赖不变就不重算）
   *   actions 是"执行操作"
   */
  getters: {
    /**
     * 是否已登录。
     *
     * ★ 为什么用 !! 转成布尔？
     *   accessToken 是字符串，'' 是假值。
     *   !!'' → false，!!'abc' → true。
     *   返回明确的布尔值，比返回字符串更不容易用错。
     *   （其实直接写 !!accessToken 也行，但返回空字符串在 if 里也能用，
     *     所以这是风格选择，不是必须。）
     */
    isLoggedIn: (state) => !!state.accessToken,

    /**
     * 界面上显示的名字。
     *
     * ★ 为什么有三级兜底？
     *   nickname → 昵称（用户设的）
     *   username → 登录名（注册时的）
     *   '未登录' → 都没有
     *
     *   保证界面永远不会显示 "undefined" 这种尴尬内容。
     */
    displayName: (state) =>
      state.profile?.nickname || state.profile?.username || '未登录'
  },

  /**
   * ═══════════════════════════════════════════════════════════════
   *  【actions】执行操作
   * ═══════════════════════════════════════════════════════════════
   */
  actions: {
    /**
     * 保存 token（同时写 state 和 localStorage）。
     *
     * ★ 为什么抽成一个方法？
     *   因为有三处要用：login、refresh（在 http.js 里）、register。
     *   如果每处都写两行 localStorage.setItem，容易漏。
     *
     * ★ 参数是解构的 { accessToken, refreshToken } ——
     *   这样可以直接传后端返回的 data 对象：
     *       user.setTokens(res.data.data)
     *   不用手动拆两个字段。
     */
    setTokens({ accessToken, refreshToken }) {
      this.accessToken = accessToken
      this.refreshToken = refreshToken
      localStorage.setItem('accessToken', accessToken)
      localStorage.setItem('refreshToken', refreshToken)
    },

    /**
     * 登录。
     *
     * @param {{username:string, password:string}} form
     * @returns {Promise<object>} 后端返回的完整登录响应
     *
     * ★ 为什么要 return data？
     *   调用方（LoginView）可能想用 expiresIn 做"提前刷新"，
     *   或者根据返回内容做额外处理。返回出去更灵活，不用也不亏。
     */
    async login(form) {
      const data = await authApi.login(form)

      /*
       * ★ 注意顺序：先存 token，再存 profile。
       *   反过来也行，但先存 token 更符合直觉
       *   （有了身份才有资料）。
       */
      this.setTokens(data)
      this.profile = data.user
      return data
    },

    /**
     * 注册 + 自动登录。
     *
     * ★ 为什么注册后要自动调 login？
     *   因为后端注册接口只返回 UserVO，不返回 token。
     *   如果不自动登录，用户注册完还要再输一次密码 —— 体验很差。
     *
     * ★ 为什么不干脆让后端注册时直接返回 token？
     *   那样注册接口的职责就变重了（既创建用户又签发凭证），
     *   而且前端要处理两套返回结构。
     *   分开之后：注册就是注册，登录就是登录，各自职责清晰。
     *
     *   ★ 前端用一行代码（再调一次 login）换来了后端的简洁，
     *     这是合理的取舍。多一次 HTTP 请求，但只发生在注册时。
     *
     * @param {{username, password, nickname}} form
     */
    async register(form) {
      await authApi.register(form)

      return this.login({
        username: form.username,
        password: form.password
      })
    },

    /**
     * 拉取最新的用户资料。
     *
     * ★ 什么时候调？
     *   1. 刷新页面后（router 守卫里自动调）
     *   2. 登录后其实已经有 profile 了，但为了保险也可以调
     *
     * ★ 为什么不在 state 初始化时就调？
     *   因为 store 创建时可能还没登录，会得到一个 401。
     *   由使用方决定何时调更合理。
     */
    async fetchProfile() {
      this.profile = await userApi.me()
      return this.profile
    },

    /**
     * 登出。
     *
     * 【★ 一个重要细节：为什么用 try/finally？】
     *
     *   登出请求可能失败（网络断了、token 已过期）。
     *   但无论成功失败，【本地状态都必须清空】——
     *   否则用户点了"退出"，却因为网络问题还停留在登录态，很困惑。
     *
     *   ★ 所以：
     *      try     → 尽力通知服务端（拉黑 token）
     *      finally → 无论如何都清本地 + 跳登录页
     *
     *   这是"本地状态优先"的设计：用户的意图（我要退出）比
     *   服务端的确认更重要。
     */
    async logout() {
      try {
        /*
         * ★ 传 refreshToken 是为了让服务端把它从白名单删掉。
         *   只传 access 的话，refresh 还能换新 access —— 登出不彻底。
         *
         * ★ access 不用传 —— 它在请求头里，拦截器自动加了。
         */
        await authApi.logout({ refreshToken: this.refreshToken })
      } catch {
        /*
         * 忽略错误：可能 token 已过期、网络断了。
         * 不影响本地清理。
         */
      } finally {
        this.clear()

        /*
         * ★ 用 window.location 而不是 router.push，原因同 http.js：
         *   避免循环依赖（router → views → stores → api → router）。
         *
         * ★ 而且跳转前先 clear()，保证新页面加载时是"未登录"状态。
         */
        window.location.href = '/login'
      }
    },

    /**
     * 清空本地登录态。
     *
     * ★ 这个方法在三个地方被调：
     *   1. logout()          正常退出
     *   2. http.js 刷新失败   登录态已失效
     *   3. ProfileView 改密码 后端踢了所有设备
     *
     * ★ 为什么用 $reset() 不够？
     *   $reset() 只重置 state 到初始值，但初始值是从 localStorage 读的！
     *   所以必须先删 localStorage，再 $reset ——
     *   或者像下面这样手动把每个字段清空。
     *
     *   ★ 这里选择手动清空，因为更明确：一眼能看出清了哪三样东西。
     */
    clear() {
      this.accessToken = ''
      this.refreshToken = ''
      this.profile = null

      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
    }
  }
})
