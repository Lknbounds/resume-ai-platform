<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个组件是什么】
 *  业务页面的外壳：顶部导航 + 内容区。
 *
 *  【★ 视觉参考】
 *  getresumeiq.com 的实际设计：
 *      - 青绿主色 #29A399 + 青绿→青的渐变
 *      - 冷灰背景 #F6F7F9，白色卡片
 *      - 12-14px 圆角
 *      - 主按钮 hover 时有柔和的外发光
 *      - 大量留白，少装饰
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 导航项顺序 = 用户的实际动线】
 *
 *      分析  →  简历  →  JD  →  论坛
 *
 *      想分析一份简历   → 从"分析"进
 *      想管理已有简历   → 从"简历"进
 *      想管岗位         → 从"JD"进
 *      想看别人怎么说   → 从"论坛"进
 *
 *  ★ 不把"论坛"放前面 —— 主流程是"我先分析好，再去社区"。
 *    次要功能前置会让新用户迷失。
 */
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useFeatureStore } from '@/stores/feature'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const feature = useFeatureStore()

/**
 * 当前应该高亮的导航项。
 *
 * ★ 为什么要按前缀匹配？
 *   访问 /resumes/1 时，route.path 是 "/resumes/1"，
 *   但对应的菜单项是 "/resumes" —— 需要前缀匹配才能高亮。
 *
 * ★ 为什么首页要特殊处理？
 *   所有路径都以 "/" 开头，如果不单独判断，
 *   在任何页面首页都会高亮。
 */
const activeMenu = computed(() => {
  const p = route.path
  if (p === '/') return '/'
  const order = ['/analysis', '/resumes', '/jds', '/community', '/interview']
  return order.find((m) => p === m || p.startsWith(m + '/')) || p
})

/**
 * 导航项。
 * ★ "面试"入口由 interviewModuleEnabled 控制；当前本地题库不依赖 AI。
 */
const menus = computed(() => {
  const list = [
    { path: '/analysis/new', label: '分析', match: '/analysis' },
    { path: '/resumes', label: '简历', match: '/resumes' },
    { path: '/jds', label: 'JD', match: '/jds' },
    { path: '/community', label: '论坛', match: '/community' }
  ]

  if (feature.interviewModuleEnabled) {
    list.push({ path: '/interview', label: '面试', match: '/interview' })
  }

  return list
})

function isActive(item) {
  return activeMenu.value === item.match
}

function onCommand(cmd) {
  if (cmd === 'logout') user.logout()
  if (cmd === 'profile') router.push('/me/profile')
  if (cmd === 'posts') router.push('/me/posts')
}
</script>

<template>
  <div class="layout">
    <!-- ═══════════ 顶栏 ═══════════ -->
    <header class="header">
      <div class="header-inner">
        <!-- 左：品牌 -->
        <div class="brand" @click="router.push('/')">
          <!--
            ★ 品牌符号：青绿渐变的圆角方块 + "简"字
              比纯圆点更有辨识度，又不依赖图片资源
          -->
          <span class="logo">简</span>
          <span class="brand-text">简评</span>
        </div>

        <!-- 中：导航 -->
        <nav class="nav">
          <router-link
            v-for="m in menus"
            :key="m.match"
            :to="m.path"
            class="nav-item"
            :class="{ active: isActive(m) }"
          >
            {{ m.label }}
          </router-link>
        </nav>

        <!-- 右：用户 -->
        <el-dropdown @command="onCommand" trigger="click">
          <span class="user">
            <el-avatar :size="26" class="avatar">
              {{ user.displayName.slice(0, 1) }}
            </el-avatar>
            <span class="user-name">{{ user.displayName }}</span>
          </span>

          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人资料</el-dropdown-item>
              <el-dropdown-item command="posts">我的帖子</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- ═══════════ 内容区 ═══════════ -->
    <main class="main">
      <div class="main-inner">
        <slot />
      </div>
    </main>
  </div>
</template>

<style scoped>
.layout {
  min-height: 100vh;
  background: var(--bg);
}

/* ─────────── 顶栏 ─────────── */

.header {
  /*
   * ★ position: sticky + backdrop-filter: blur
   *   滚动时顶栏固定，且下面内容模糊透出 —— 这是现代产品的常见做法。
   *   Linear / Vercel / Stripe 都这么做。
   *
   * ★ 半透明白 + 模糊，比纯白更有"浮在内容上方"的感觉。
   */
  position: sticky;
  top: 0;
  z-index: 100;

  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);

  border-bottom: 1px solid var(--line-soft);
}

/*
 * ★ 为什么要有 header-inner 这层？
 *   让顶栏背景铺满整个宽度（模糊效果才自然），
 *   但内容限制在 1080px 内居中 —— 和下方内容区对齐。
 */
.header-inner {
  display: flex;
  align-items: center;
  height: 56px;
  padding: 0 24px;

  max-width: 1080px;
  margin: 0 auto;
}

/* ─────────── 品牌 ─────────── */

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}

.logo {
  width: 24px;
  height: 24px;
  border-radius: 7px;

  /* ★ 品牌渐变 —— 整个界面最"亮"的一个点 */
  background: var(--gradient-brand);

  display: flex;
  align-items: center;
  justify-content: center;

  color: #fff;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: -0.02em;

  /* 轻微发光，增加质感 */
  box-shadow: 0 2px 8px rgba(41, 163, 153, 0.3);
}

.brand-text {
  font-size: 15px;
  font-weight: 700;
  color: var(--text);
  letter-spacing: -0.02em;
}

/* ─────────── 导航 ─────────── */

.nav {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: 28px;
  flex: 1;
}

.nav-item {
  padding: 6px 11px;
  border-radius: 7px;

  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
  text-decoration: none;

  transition: color 0.15s, background 0.15s;
}

.nav-item:hover {
  color: var(--text);
  background: var(--fill);
}

/*
 * ★ 选中态用"浅青底 + 深青字"，而不是下划线或实心主色。
 *   实心主色太重（一排按钮会打架），浅底更克制。
 */
.nav-item.active {
  color: var(--brand-deep);
  background: var(--brand-soft);
  font-weight: 600;
}

/* ─────────── 用户 ─────────── */

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px 4px 4px;
  border-radius: 999px;
  transition: background 0.15s;
  outline: none;
  flex-shrink: 0;
}

.user:hover {
  background: var(--fill);
}

.avatar {
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 12px;
  font-weight: 600;
}

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

/* ─────────── 内容区 ─────────── */

.main {
  padding: 40px 24px 80px;
}

/*
 * ★ 1080px 是内容型页面的常用最大宽度。
 *   再宽的话，一行文字太长，眼睛扫读会累（这叫"阅读舒适线"）。
 */
.main-inner {
  max-width: 1080px;
  margin: 0 auto;
}

/* ─────────── 窄屏 ─────────── */

@media (max-width: 720px) {
  .header-inner {
    padding: 0 16px;
  }

  .nav {
    margin-left: 12px;
    gap: 0;
  }

  .nav-item {
    padding: 6px 8px;
    font-size: 12px;
  }

  /* 窄屏隐藏用户名，只留头像，省空间 */
  .user-name {
    display: none;
  }

  .main {
    padding: 24px 16px 56px;
  }
}
</style>
