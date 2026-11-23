import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useFeatureStore } from '@/stores/feature'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  路由表 + 路由守卫。决定"什么 URL 显示什么页面""什么情况能进去"。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：用户点一次导航发生了什么】
 *
 *      用户点导航栏"我的简历"
 *         ↓
 *      Vue Router 匹配到 /resumes
 *         ↓
 *      【beforeEach 守卫】★ 本文件的核心
 *         ├─ 功能开关拉过了吗？没拉过 → 拉一次
 *         ├─ 这个页面要登录吗？要 + 没登录 → 跳 /login
 *         ├─ 这个页面依赖某个功能吗？功能没开 → 跳首页
 *         ├─ 已登录但 profile 为空？→ 拉一次资料
 *         └─ 全部通过 → return true
 *         ↓
 *      渲染 views/resume/ResumeListView.vue
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 路由表的三类 meta 配置】
 *
 *      requiresAuth: true   → 必须登录
 *      feature: 'interview' → 依赖某个功能开关，没开就进不去
 *      title: '首页'        → 浏览器标签页标题
 */
const routes = [
  // ═══════════════ 认证页（不需要登录） ═══════════════
  {
    path: '/login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    component: () => import('@/views/auth/RegisterView.vue'),
    meta: { title: '注册' }
  },

  // ═══════════════ 首页 ═══════════════
  {
    path: '/',
    component: () => import('@/views/dashboard/DashboardView.vue'),
    meta: { requiresAuth: true, title: '首页' }
  },

  // ═══════════════ 用户 ═══════════════
  {
    path: '/me/profile',
    component: () => import('@/views/me/ProfileView.vue'),
    meta: { requiresAuth: true, title: '个人资料' }
  },
  {
    path: '/me/posts',
    component: () => import('@/views/me/MyPostsView.vue'),
    meta: { requiresAuth: true, title: '我的帖子' }
  },

  // ═══════════════ 简历（步骤 5） ═══════════════
  {
    path: '/resumes',
    component: () => import('@/views/resume/ResumeListView.vue'),
    meta: { requiresAuth: true, title: '我的简历' }
  },
  {
    path: '/resumes/:id',
    component: () => import('@/views/resume/ResumeDetailView.vue'),
    meta: { requiresAuth: true, title: '简历详情' }
  },

  // ═══════════════ JD（步骤 7） ═══════════════
  {
    path: '/jds',
    component: () => import('@/views/jd/JdListView.vue'),
    meta: { requiresAuth: true, title: '岗位 JD' }
  },

  // ═══════════════ 分析（步骤 8-9） ═══════════════
  {
    path: '/analysis/new',
    component: () => import('@/views/analysis/AnalysisCreateView.vue'),
    meta: { requiresAuth: true, title: '分析简历' }
  },
  {
    path: '/analysis/:id',
    component: () => import('@/views/analysis/AnalysisReportView.vue'),
    meta: { requiresAuth: true, title: '分析报告' }
  },

  // ═══════════════ 社区（步骤 12-14） ═══════════════
  {
    path: '/community',
    component: () => import('@/views/community/PostListView.vue'),
    meta: { title: '社区' }
  },
  {
    path: '/community/:id',
    component: () => import('@/views/community/PostDetailView.vue'),
    meta: { title: '帖子详情' }
  },

  // 步骤 6：版本对比
  //   /resumes/:id/compare        版本对比

  // ═══════════════ 面试预演（本地 5 题） ═══════════════
  {
    path: '/interview',
    component: () => import('@/views/interview/InterviewPracticeView.vue'),
    meta: { requiresAuth: true, feature: 'interview', title: '面试预演' }
  },

  // ═══════════════ 兜底 ═══════════════
  {
    /*
     * ★ pathMatch(.*)* 匹配所有未定义的路径。
     *
     * ★ 为什么要这个？
     *   用户输入 /abcdefg，如果不匹配任何路由，Vue Router 会显示空白页。
     *   重定向到首页更友好。
     *
     * ★ 注意：这和"后端返回 404"是两回事。
     *   这里是前端路由（浏览器地址栏），后端 404 是接口层面的。
     */
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  /**
   * ═══════════════════════════════════════════════════════════════
   *  【createWebHistory】history 模式（URL 里没有 #）
   * ═══════════════════════════════════════════════════════════════
   *
   * ★ 两种模式对比：
   *
   *   createWebHistory  →  /resumes/1        ← 好看（本项目用这个）
   *   createWebHashHistory → /#/resumes/1     ← 丑，但部署简单
   *
   * ★ 用 history 模式的代价：需要服务端配置"SPA fallback"。
   *
   *   原因：用户直接访问 /resumes/1 时，浏览器会向服务器要这个路径，
   *        但服务器上并没有 /resumes/1 这个文件（只有一个 index.html）。
   *        所以必须配置"找不到文件就返回 index.html"，
   *        让前端路由接管。
   *
   *   我们的配置：
   *      Vite 开发服务器：自动处理（内置了 fallback）
   *      Nginx 生产：try_files $uri $uri/ /index.html;   ← 见 Front/nginx.conf
   *
   *   ★ 如果忘了配这个，用户刷新页面就会 404 —— 这是部署时最常踩的坑。
   */
  history: createWebHistory(),
  routes,

  /**
   * 切换页面时滚动到顶部。
   *
   * ★ 为什么需要？
   *   默认行为是"保持滚动位置"。从长列表点进详情页，
   *   会停在中间位置 —— 用户会以为页面没变。
   */
  scrollBehavior() {
    return { top: 0 }
  }
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【路由守卫】每次导航前执行
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 返回值决定导航结果：
 *      return true              → 放行
 *      return { path: '/login' }→ 重定向到别的页面
 *      return false             → 取消导航（页面不变）
 *      （返回 undefined 也等价于放行）
 *
 * ★ 为什么守卫是 async？
 *   因为要等"拉功能开关""拉用户资料"这些异步操作完成再决定。
 *   ★ 注意：守卫里 await 会【阻塞导航】，直到完成才渲染页面。
 *     所以不能在里面放很慢的操作，否则用户会看到"点了没反应"。
 */
router.beforeEach(async (to) => {
  const user = useUserStore()
  const feature = useFeatureStore()

  // ─────────────────────────────────────────────────────────────
  // 【一、拉功能开关】只拉一次
  // ─────────────────────────────────────────────────────────────
  if (!feature.loaded) {
    try {
      await feature.load()
    } catch {
      /*
       * ★ 为什么 catch 掉不报错？
       *   如果后端没启动，功能开关拉不到。
       *   这时候不应该让整个前端卡死（连登录页都进不去），
       *   而是用默认值继续（所有 AI 功能都按 false 处理）。
       *
       *   ★ 用户至少还能看到登录页，知道"后端可能有问题"，
       *     而不是一个白屏。
       */
    }
  }

  // ─────────────────────────────────────────────────────────────
  // 【二、登录检查】
  // ─────────────────────────────────────────────────────────────
  if (to.meta.requiresAuth && !user.isLoggedIn) {
    /*
     * ★ 为什么要带 redirect 参数？
     *   用户想访问 /me/profile，被踢到登录页。
     *   登录成功后应该回到 /me/profile，而不是首页。
     *
     *   LoginView 里这样用：
     *       router.replace(route.query.redirect || '/')
     *
     *   ★ 这是"登录后回到原页面"的标准做法。
     */
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // ─────────────────────────────────────────────────────────────
  // 【三、功能开关检查】★ AI 关闭时整个模块消失
  // ─────────────────────────────────────────────────────────────
  if (to.meta.feature === 'interview' && !feature.interviewModuleEnabled) {
    /*
     * ★ 这是"三重保障"的第二层：
     *      1. 导航栏 v-if        （看不见入口）
     *      2. 路由守卫           （直接输 URL 也进不去）← 这里
     *      3. 接口返回 44001     （真正的边界）
     *
     * ★ 为什么守卫里返回首页而不是显示一个"功能未开启"页面？
     *   因为"模块整体不可见"的语义是"用户根本不该知道有这个功能"。
     *   显示一个"功能未开启"的页面，等于告诉用户"这里本来有个东西"。
     *
     *   ★ 不过给一个 ElMessage 提示是必要的 ——
     *     否则用户会以为页面坏了。提示"功能未开启"而不说具体是什么。
     */
    ElMessage.warning('该功能当前未开启')
    return { path: '/' }
  }

  // ─────────────────────────────────────────────────────────────
  // 【四、补拉用户资料】
  // ─────────────────────────────────────────────────────────────
  if (user.isLoggedIn && !user.profile) {
    /*
     * ★ 为什么要在这里拉？
     *   刷新页面后内存清空了，但 localStorage 里还有 token。
     *   此时 isLoggedIn 为 true，但 profile 是 null。
     *   导航栏要显示昵称、个人资料页要填表单，都需要 profile。
     *
     *   在这里统一拉一次，所有页面都能直接用。
     *
     * ★ 为什么在守卫里而不是 app.vue 的 onMounted？
     *   守卫能 await，保证"页面渲染时 profile 已经有了"，
     *   不会出现"先显示'未登录'再变成'Alice'"的闪烁。
     *
     * ★ 失败时（token 过期）：
     *   http.js 的拦截器会尝试刷新，刷新失败会跳登录页。
     *   这里 catch 掉避免"守卫抛异常导致导航卡死"。
     */
    try {
      await user.fetchProfile()
    } catch {
      // 401 已由 http.js 处理（刷新或跳登录页）
    }
  }

  // ─────────────────────────────────────────────────────────────
  // 【五、设置页面标题】
  // ─────────────────────────────────────────────────────────────
  /*
   * ★ 为什么放在守卫里而不是每个页面的 onMounted？
   *   每个页面都写一遍太重复，而且容易忘。
   *   用 meta.title 统一管理，新增页面只改路由表。
   */
  document.title = `${to.meta.title || '首页'} · 简评`

  return true
})

export default router
