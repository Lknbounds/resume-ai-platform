import http from './http'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  认证相关的 API 定义。4 个方法，对应后端 4 个接口。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么要单独一个文件，不直接在页面里写 http.post？】
 *
 *  1. 【接口集中】所有认证接口在一起，看这个文件就知道有哪些能力
 *  2. 【路径只写一次】改了后端路径只改这里，不用满项目搜
 *  3. 【页面更干净】页面里写 authApi.login(form)，
 *     而不是 http.post('/auth/login', form) —— 前者一眼看出在干什么
 *  4. 【方便 mock】测试时可以替换整个 authApi
 *
 *  ★ 这是前端的分层：页面 → api 层 → http 层 → 后端
 *    对应后端的 Controller → Service → Repository
 *
 *
 *  【★ 返回值为什么不需要 .data？】
 *  因为 http.js 的响应拦截器已经剥过壳了。
 *  后端返回 {"code":0,"data":{"accessToken":...}}
 *  这里直接拿到 {"accessToken":...}
 *
 *  ★ 对比一下两种写法的差别：
 *      const res = await http.post('/auth/login', form)
 *      const token = res.data.accessToken        ← 没剥壳时
 *
 *      const data = await authApi.login(form)
 *      const token = data.accessToken            ← 剥壳后
 */
export const authApi = {
  /**
   * 注册。
   *
   * @param {{username:string, password:string, nickname:string}} data
   * @returns {Promise<{id:number, username:string, nickname:string, ...}>}
   *
   * ★ 注意：注册成功后【不会】自动登录（后端不返回 token）。
   *   前端要再调一次 login。见 stores/user.js 的 register 方法。
   */
  register: (data) => http.post('/auth/register', data),

  /**
   * 登录。
   *
   * @param {{username:string, password:string}} data
   * @returns {Promise<{accessToken:string, refreshToken:string, expiresIn:number, user:object}>}
   *
   * 错误（被拦截器 reject）：
   *      code=20002  用户名或密码错误
   */
  login: (data) => http.post('/auth/login', data),

  /**
   * 登出。
   *
   * @param {{refreshToken:string}} data
   *
   * ★ 为什么要把 refreshToken 传过去？
   *   只传 access 的话，refresh 还能换新 access —— 登出不彻底。
   *   两个都要处理（见后端 AuthService.logout 的说明）。
   *
   * ★ 注意：这个请求会【自动带上 access token 请求头】
   *   （http.js 的请求拦截器做的），所以后端能拿到两个 token。
   */
  logout: (data) => http.post('/auth/logout', data),

  /**
   * 刷新 token。
   *
   * ★ 前端一般不直接调这个方法 ——
   *   它是给 http.js 的 refreshOnce() 用的。
   *   这里暴露出来只是为了完整性（调试时可能需要手动调）。
   *
   * ★ 注意：http.js 里的刷新用的是【裸 axios】而不是这个 http 实例，
   *   否则会无限递归。所以这个方法实际上很少被业务代码调用。
   */
  refresh: (data) => http.post('/auth/refresh', data)
}
