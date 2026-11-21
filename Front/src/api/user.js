import http from './http'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  用户资料相关的 API。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 注意路径都是 /users/me，没有 /users/{id}】
 *
 *  这不是偷懒，是刻意的安全设计：
 *      /users/me  → 服务端从 token 推导"我是谁"
 *      /users/7   → 客户端指定"我要看 7 号" ← 需要额外校验，漏了就成越权
 *
 *  ★ 用 /me 的时候，客户端【没有传 id 的机会】，
 *    从设计上就不可能越权。
 */
export const userApi = {
  /**
   * 查当前登录用户资料。
   *
   * @returns {Promise<{id, username, nickname, targetPosition, yearsOfExperience, createdAt, lastLoginAt}>}
   *
   * ★ 什么时候调？
   *   1. 登录后（虽然登录响应里已经有 user，但刷新页面后需要重新拿）
   *   2. 路由守卫里（见 router/index.js：如果 store 里没有 profile 就拉一次）
   */
  me: () => http.get('/users/me'),

  /**
   * 修改个人资料。
   *
   * @param {{nickname:string, targetPosition:string|null, yearsOfExperience:number|null}} data
   * @returns {Promise<UserVO>} 更新后的完整资料
   *
   * ★ 返回的是完整资料而不是 void ——
   *   前端拿到后直接赋值给 store，界面立刻更新，不用再查一次。
   */
  updateProfile: (data) => http.put('/users/me', data),

  /**
   * 修改密码。
   *
   * @param {{oldPassword:string, newPassword:string}} data
   * @returns {Promise<null>}
   *
   * ★★ 成功后前端必须做的事：
   *       user.clear()                    清空本地 token
   *       window.location.href = '/login' 跳登录页
   *
   *   因为后端已经把该用户所有 refresh token 清空了，
   *   当前 access 虽然还能用 2 小时，但已经无法续期。
   *   与其让用户用一会儿突然被踢，不如主动引导重新登录。
   *
   *   见 views/me/ProfileView.vue 的 changePassword 方法。
   */
  changePassword: (data) => http.put('/users/me/password', data)
}
