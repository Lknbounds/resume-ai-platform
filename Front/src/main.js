import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'

/*
 * ★★ 顺序极其重要：Element Plus 的样式必须先导入，我们的主题后导入。
 *
 *   因为 CSS 的规则是"后写的覆盖先写的"（同优先级时）。
 *   如果 theme.css 在前面，Element 的默认样式会把它覆盖掉 ——
 *   你的青绿主色就变成默认蓝了。
 *
 *   ★ 这是"换个 UI 库主题"最常见的问题：样式顺序不对，改了等于没改。
 */
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/theme.css'
import './styles/index.css'

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个文件是什么】
 *  前端的启动入口。相当于后端的 ResumeAiApplication。
 *
 *  它做的事，按顺序：
 *      1. 创建 Vue 应用
 *      2. 注册图标组件
 *      3. 安装 Pinia（状态管理）
 *      4. 安装 Router（路由）
 *      5. 安装 Element Plus（UI 组件库）
 *      6. 挂载到 index.html 里的 #app
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 顺序为什么重要？】
 *
 *  ★★ useRouter / useStore 必须在 app.use(...) 之后才能用。
 *
 *     如果 router/index.js 在模块加载时就调 useUserStore()，
 *     Pinia 还没安装 → 报 "getActivePinia() was called but there was no active Pinia"
 *
 *   ★ 所以：
 *     - 组件里的 setup() 中调 useStore  → 没问题（那时已安装）
 *     - 模块顶层直接调 useStore        → 报错
 *     - 函数内部调（比如拦截器里的）    → 没问题（调用时才执行）
 *
 *   这就是为什么 http.js 里要在【拦截器函数内部】调 useUserStore()，
 *   而不是在文件顶部。
 */

const app = createApp(App)

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【注册图标】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Element Plus 的图标是独立包，需要手动注册。
 *
 * ★ 这里用【全量注册】（把几百个图标全部注册一遍），
 *   优点是省事 —— 模板里直接写 <el-icon><Document /></el-icon> 就行。
 *
 * ⚠️ 缺点是打包体积大（约 1MB 未压缩）。
 *   正式项目应该按需引入：
 *       import { Document, Plus } from '@element-plus/icons-vue'
 *       app.component('Document', Document)
 *       app.component('Plus', Plus)
 *
 *   ★ 本项目为了开发效率选全量。
 *     如果面试被问"怎么优化打包体积"，可以答：
 *     "图标按需引入 + 路由懒加载 + 第三方库分包"。
 *     （路由懒加载我们已经做了：component: () => import(...)）
 */
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

/**
 * ★ Pinia 必须在 Router 之前安装。
 *   因为 router 守卫里要用 store。
 */
app.use(createPinia())

app.use(router)

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【安装 Element Plus】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ { locale: zhCn } 是什么？
 *   把组件内置的文字换成中文（分页的"共 10 条"、日期选择器的"一月"等）。
 *   不配的话是英文。
 *
 * ★ 注意：locale 只影响"组件自带的文字"，
 *   你自己写的文字不受影响。
 */
app.use(ElementPlus, { locale: zhCn })

/**
 * ★ mount 必须是最后一步 —— 要等所有插件装好再挂载。
 *   在 mount 之前，DOM 里还只有一个空的 <div id="app"></div>。
 */
app.mount('#app')
