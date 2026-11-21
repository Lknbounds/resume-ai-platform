import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  所有 HTTP 请求的统一入口。整个前端只有这一个 axios 实例，
 *  所有 api/*.js 都基于它发请求。
 *
 *  它做四件事：
 *      1. 每个请求自动带上 Authorization 头
 *      2. 每个响应自动"拆壳"（只把 data 给业务代码）
 *      3. 401 时自动刷新 token 并重放请求
 *      4. 统一弹出错误提示
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：一次 api 调用的完整过程】
 *
 *      页面里：const list = await resumeApi.list()
 *         ↓
 *      【本文件】请求拦截器
 *         ├─ 从 useUserStore 取出 accessToken
 *         └─ 加上请求头：Authorization: Bearer eyJ...
 *         ↓
 *      axios 发 HTTP 请求
 *         ↓
 *      后端返回：{"code":0,"message":"ok","data":{...}}
 *         ↓
 *      【本文件】响应拦截器
 *         ├─ code === 0  →  return body.data      ← ★ 只返回 data，剥掉外壳
 *         └─ code !== 0  →  ElMessage.error + reject
 *         ↓
 *      页面里：list 直接就是业务数据
 *         ★ 所以页面里写 list.records，而不是 list.data.records
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么用 baseURL: '/api'？】
 *
 *  因为开发时 Vite 会代理（见 vite.config.js）：
 *      '/api' → http://localhost:8080
 *  生产时 Nginx 会反代（见 nginx.conf）：
 *      location /api/ { proxy_pass http://backend:8080; }
 *
 *  ★ 前端代码里永远只写相对路径，环境差异由代理层处理。
 *    这样同一份代码在开发和生产都能跑，不用改任何地方。
 *    如果写成 http://localhost:8080/api，生产环境就废了。
 */
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',

  /**
   * 超时时间 60 秒。
   *
   * ★ 为什么这么长？
   *   普通的增删改查 1 秒内就返回，但 AI 相关接口可能要 10-30 秒。
   *   设太短会导致"请求还在跑，前端已经报超时"。
   *
   * ★ 那不能设无限吗？
   *   不能。如果后端真的卡死，前端会一直转圈永远不结束。
   *   60 秒是个上限，超过就说明后端有问题。
   *
   * ★ 注意：分析任务是异步的（返回 202 + reportId），
   *   所以不会真的等 60 秒 —— 进度靠 SSE 或轮询获取。
   */
  timeout: 60000
})

// ═══════════════════════════════════════════════════════════════════════
//  【请求拦截器】每个请求发出前执行
// ═══════════════════════════════════════════════════════════════════════
http.interceptors.request.use((config) => {
  /*
   * ★ 为什么在拦截器里取 store，而不是在文件顶部 import 时就取？
   *   Pinia 的 store 必须在 app.use(createPinia()) 之后才能用。
   *   如果文件加载时就调用 useUserStore()，会报
   *   "getActivePinia() was called but there was no active Pinia"。
   *
   *   ★ 放在函数内部，等到真正发请求时才调用，那时 Pinia 早就初始化好了。
   */
  const user = useUserStore()

  if (user.accessToken) {
    /*
     * 标准格式：Authorization: Bearer <token>
     * ★ "Bearer " 后面有一个空格，少了会验证失败。
     */
    config.headers.Authorization = `Bearer ${user.accessToken}`
  }

  return config
})

// ═══════════════════════════════════════════════════════════════════════
//  【响应拦截器】每个响应回来后执行
// ═══════════════════════════════════════════════════════════════════════
http.interceptors.response.use(
  /**
   * 第一个参数：HTTP 状态码是 2xx 时走这里。
   *
   * ★ 注意：这里的"成功"指的是 HTTP 层面成功（2xx），
   *   并不代表业务成功。业务成功要看 body.code === 0。
   *
   *   比如"用户名已存在"返回的是 HTTP 409 + {"code":20001,...}，
   *   HTTP 409 不是 2xx，所以会走下面的第二个参数（错误分支）。
   *
   *   ★ 但也有例外：后端约定 43002（审核不通过）虽然 HTTP 是 422，
   *     前端需要特殊处理 —— 这个在错误分支里处理，见下面。
   */
  (res) => {
    if (res.config?.responseType === 'blob' || res.config?.responseType === 'arraybuffer') {
      return res.data
    }

    const body = res.data

    /*
     * 【业务成功】→ 剥掉外壳，只返回 data
     *
     * ★ 为什么要"剥壳"？
     *   后端返回 {"code":0,"data":{"records":[...]}}
     *   如果不剥，页面里要写 res.data.data.records —— 三个 data，很难看。
     *   剥掉之后页面里写 res.records，清爽。
     */
    if (body.code === 0) {
      return body.data
    }

    /*
     * 【★ 43002 审核不通过：特殊处理，不弹提示】
     *
     * ★ 为什么它要特殊对待？
     *   因为这个错误需要弹出【带高亮定位的对话框】，
     *   而这个 UI 只有发布页知道怎么做。
     *   如果在这里弹一个通用的 toast，用户只会看到"内容含个人信息"，
     *   却不知道具体是哪里、也没法一键定位。
     *
     * ★ 所以约定：拦截器对 43002 只 reject，交给页面 catch 处理。
     *   见 12 步骤的 PublishDialog.vue。
     */
    if (body.code === 43002) {
      return Promise.reject(body)
    }

    /*
     * 【其他业务失败】→ 弹提示 + reject
     *
     * ★ 为什么在拦截器里弹提示？
     *   因为 90% 的错误都是"提示一下就够"的（比如"简历不存在"）。
     *   如果每个页面都写 try-catch + ElMessage.error，重复几十次。
     *   放在这里，页面只需要 catch 后不做任何事（或者做特殊处理）。
     *
     * ★ 那页面怎么知道"要不要额外处理"？
     *   看 e.code。比如 43002 就要额外处理，其他不用。
     */
    ElMessage.error(body.message || '请求失败')
    return Promise.reject(body)
  },

  /**
   * 第二个参数：HTTP 状态码不是 2xx，或者网络错误时走这里。
   *
   * ★ 什么时候会走到这？
   *   1. 401 未登录 / token 过期   ← 下面重点处理
   *   2. 403 没权限
   *   3. 404 路径不存在
   *   4. 429 限流
   *   5. 500 服务器错误
   *   6. 422 个人信息审核不通过      ← 特殊处理
   *   7. 网络断开（response 为 undefined）
   *
   * ★ 注意区分：后端的"业务异常"（比如用户名已存在）返回的是 HTTP 409，
   *   所以也会走到这里，而不是上面那个成功分支。
   */
  async (err) => {
    const { response, config } = err

    /*
     * ═══════════════════════════════════════════════════════════
     *  【情况一：422 + 43002 审核不通过】
     * ═══════════════════════════════════════════════════════════
     *
     * ★ 为什么不弹提示？
     *   和上面同样的理由 —— 交给发布页做高亮对话框。
     *
     * ★ 为什么用 status === 422 而不是 code === 43002 判断？
     *   两个都可以。用 HTTP 码的好处是：不需要先解构 body，
     *   而且 422 在语义上就对应"内容不可处理"，一眼能看懂。
     *   （其实这里 response.data.code 也是 43002，两种判断等价。）
     */
    if (response?.status === 422 && response.data?.code === 43002) {
      return Promise.reject(response.data)
    }

    /*
     * ═══════════════════════════════════════════════════════════
     *  【情况二：401 未登录 / token 过期】★ 最复杂的一段
     * ═══════════════════════════════════════════════════════════
     *
     * 【背景：为什么要自动刷新？】
     *   access token 只有 2 小时。用户用着用着就过期了。
     *   如果不自动刷新，用户每两小时被踢一次，体验极差。
     *
     * 【做法】
     *   收到 401 → 调 /auth/refresh 换新 token → 用新 token 重放原请求
     *   整个过程用户无感。
     *
     * 【★ 难点：并发请求】
     *   页面加载时可能同时发 3 个请求（列表 + 详情 + 未读数），
     *   三个同时 401。如果每个都去刷新：
     *      第一个刷新成功，旧 refresh 被轮换作废
     *      第二个、第三个拿着已作废的 refresh 去刷新 → 失败 → 用户被踢
     *
     *   ★ 解决：用"单例 Promise"。第一个 401 触发刷新，
     *     后两个等同一个 Promise 完成后再各自重放。
     *     见下面的 refreshOnce()。
     *
     * 【★ 为什么判断 !config._retry？】
     *   防止无限循环：
     *      请求 → 401 → 刷新 → 重放 → 又 401（比如 token 真的无效）
     *      如果不加标记，会永远循环下去，刷爆浏览器。
     *   _retry 是个自定义标记，表示"这个请求已经重试过一次了，
     *   再失败就直接放弃"。
     */
    const requestUrl = config?.url || ''
    const isAuthRequest = /\/auth\/(login|register|refresh)\/?$/.test(requestUrl)
    const canRefreshToken = Boolean(useUserStore().refreshToken)
    if (response?.status === 401 && !isAuthRequest && canRefreshToken && !config._retry) {
      config._retry = true

      try {
        const token = await refreshOnce()

        /*
         * 用新 token 更新请求头，然后重放原请求。
         *
         * ★ 为什么要手动设置 config.headers.Authorization？
         *   因为请求拦截器已经执行过了（用的是旧 token），
         *   重放时不会再走一遍拦截器吗？
         *
         *   实际上会走（http(config) 是一个新的请求，会重新过拦截器），
         *   但拦截器读的是 store 里的 token —— 而 refreshOnce 已经
         *   把新 token 写进 store 了。所以这一行其实是"双保险"。
         *
         *   ★ 保留它没坏处，而且让代码意图更明显。
         */
        config.headers.Authorization = `Bearer ${token}`
        return http(config)
      } catch {
        /*
         * 【刷新也失败了】→ 只能让用户重新登录
         *
         * 可能原因：
         *   1. refresh token 也过期了（7 天没用）
         *   2. refresh token 被轮换掉了（另一个标签页先刷新了）
         *   3. 用户登出了
         *   4. 改密码后所有 token 被清空
         */
        useUserStore().clear()

        /*
         * ★★ 为什么用 window.location.href 而不是 router.push？
         *
         *   循环依赖问题：router → views → api/http → router
         *
         *   如果在 http.js 顶部 import router，Vite 打包时可能让
         *   router 在模块初始化阶段是 undefined，报
         *   "Cannot read properties of undefined (reading 'push')"。
         *
         *   用 window.location.href 简单粗暴，而且顺便把内存里的脏状态
         *   全部清掉（相当于重新加载应用）—— 对"登录态失效"这种场景
         *   反而更干净。
         */
        window.location.href = '/login'
        return Promise.reject(err)
      }
    }

    /*
     * ═══════════════════════════════════════════════════════════
     *  【情况三：其他错误】→ 统一提示
     * ═══════════════════════════════════════════════════════════
     *
     * ★ 优先用后端返回的 message（比如"简历不存在"），
     *   没有就用通用的"网络异常"。
     *
     * ★ 为什么可能没有 message？
     *   - 网络断开时 response 是 undefined
     *   - 后端 500 且没返回 JSON（极少见）
     */
    const msg = response?.data?.message || '网络异常，请稍后重试'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【刷新 token 的单例】
 * ═══════════════════════════════════════════════════════════════════════
 *  ★ 这是整个文件最精妙的 20 行，值得反复看。
 *
 *  【它解决什么问题】
 *  多个请求同时 401，只应该刷新【一次】，其余请求等这次刷新的结果。
 *
 *  【怎么做到的】
 *    refreshingPromise 是个模块级变量（整个前端只有一份）。
 *    第一次调用：它是 null → 发起刷新 → 把 Promise 存进去
 *    后面的调用：它不是 null → 直接返回同一个 Promise
 *    刷新结束（.finally）：置回 null，下次可以再刷新
 *
 *  ★ 这不就是"缓存进行中的异步操作"吗？对，这个模式叫
 *    "in-flight request deduplication"（进行中请求去重）。
 *
 *  【★ 为什么用 axios 而不是 http？】
 *
 *   ★★ 这一行极其关键，写错会无限递归：
 *
 *   如果用 http.post('/auth/refresh')：
 *       刷新请求失败（401）→ 又走 http 的响应拦截器 → 又尝试刷新 → ...
 *       无限递归，最终栈溢出。
 *
 *   用裸 axios：
 *       刷新请求失败不会走 http 的拦截器，直接抛异常给下面的 catch。
 *
 *   ★ 这就是为什么 refresh 必须"绕开自己的拦截器"。
 */
let refreshingPromise = null

function refreshOnce() {
  if (!refreshingPromise) {
    const user = useUserStore()

    refreshingPromise = axios
      /*
       * ★ 注意这里也用了 import.meta.env.VITE_API_BASE。
       *   不能用相对路径 '/api/auth/refresh' ——
       *   裸 axios 没有 baseURL 配置，会给当前域名拼路径。
       *   开发时当前域名是 5173，虽然代理也能处理，
       *   但显式写全更保险。
       */
      .post(`${import.meta.env.VITE_API_BASE || '/api'}/auth/refresh`, {
        refreshToken: user.refreshToken
      })
      .then((res) => {
        if (res.data.code !== 0) {
          throw new Error(res.data.message || '刷新失败')
        }

        /*
         * ★ 保存【两个】新 token，不是一个。
         *   因为后端做了"轮换" —— 旧的 refresh 已作废，
         *   不保存新的，下次刷新就会失败。
         */
        user.setTokens(res.data.data)
        return user.accessToken
      })
      .finally(() => {
        /*
         * ★ finally 里重置，保证无论成功失败都能再次刷新。
         *   如果不重置，一旦失败过就永远不能刷新了。
         */
        refreshingPromise = null
      })
  }

  return refreshingPromise
}

export default http
