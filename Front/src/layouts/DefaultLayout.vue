<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useFeatureStore } from '@/stores/feature'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const feature = useFeatureStore()

const activeMenu = computed(() => {
  const p = route.path
  if (p === '/') return '/'
  const order = ['/analysis', '/resumes', '/jds', '/community', '/interview']
  return order.find((m) => p === m || p.startsWith(m + '/')) || p
})

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
    <header class="header">
      <div class="header-inner">
        <button type="button" class="brand" aria-label="回到简评首页" @click="router.push('/')">
          <span class="logo" aria-hidden="true">简</span>
          <span class="brand-copy">
            <strong>简评</strong>
            <small>CAREER STUDIO</small>
          </span>
        </button>

        <nav class="nav" aria-label="主导航">
          <router-link
            v-for="m in menus"
            :key="m.match"
            :to="m.path"
            class="nav-item"
            :class="{ active: isActive(m) }"
            :aria-current="isActive(m) ? 'page' : undefined"
          >
            {{ m.label }}
          </router-link>
        </nav>

        <el-dropdown @command="onCommand" trigger="click">
          <button type="button" class="user" aria-label="打开账户菜单">
            <el-avatar :size="32" class="avatar">
              {{ user.displayName.slice(0, 1) }}
            </el-avatar>
            <span class="user-copy">
              <small>我的工作台</small>
              <strong>{{ user.displayName }}</strong>
            </span>
            <span class="chevron" aria-hidden="true">⌄</span>
          </button>

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
  background: transparent;
}

.header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(248, 243, 233, 0.88);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  border-bottom: 1px solid rgba(221, 212, 197, 0.82);
}

.header-inner {
  display: flex;
  align-items: center;
  min-height: 76px;
  padding: 0 28px;
  max-width: 1240px;
  margin: 0 auto;
  gap: 24px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  min-height: 48px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text);
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
  text-align: left;
}

.logo {
  width: 38px;
  height: 38px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--gradient-accent);
  box-shadow: 0 7px 18px rgba(238, 106, 75, 0.24);
  color: #fff;
  font-family: var(--font-serif);
  font-size: 20px;
  font-weight: 700;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  line-height: 1;
  gap: 5px;
}

.brand-copy strong {
  font-family: var(--font-serif);
  font-size: 20px;
  letter-spacing: 0.04em;
}

.brand-copy small {
  color: var(--text-faint);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.nav {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.nav-item {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 42px;
  padding: 0 16px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-muted);
  white-space: nowrap;
  transition: color 0.18s ease, background-color 0.18s ease;
}

.nav-item:hover {
  color: var(--text);
  background: rgba(243, 239, 230, 0.9);
}

.nav-item.active {
  color: var(--brand-deep);
  background: var(--brand-soft);
  box-shadow: inset 0 0 0 1px rgba(31, 75, 62, 0.06);
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 48px;
  padding: 5px 9px 5px 6px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  color: var(--text);
  cursor: pointer;
  flex-shrink: 0;
  text-align: left;
  transition: background-color 0.18s ease, border-color 0.18s ease;
}

.user:hover {
  background: rgba(255, 252, 247, 0.72);
  border-color: var(--line-soft);
}

.avatar {
  background: var(--brand);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.user-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.15;
}

.user-copy small {
  color: var(--text-faint);
  font-size: 9px;
  letter-spacing: 0.04em;
}

.user-copy strong {
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 600;
}

.chevron {
  color: var(--text-faint);
  font-size: 14px;
  transform: translateY(-1px);
}

.main {
  padding: 44px 28px 88px;
}

.main-inner {
  max-width: 1240px;
  margin: 0 auto;
}

@media (max-width: 840px) {
  .header-inner {
    flex-wrap: wrap;
    gap: 4px 16px;
    padding: 10px 18px 0;
  }

  .brand { margin-right: auto; }
  .user-copy, .chevron { display: none; }
  .user { padding: 4px; }

  .nav {
    order: 3;
    flex-basis: 100%;
    overflow-x: auto;
    padding: 4px 0 10px;
    scrollbar-width: none;
  }
  .nav::-webkit-scrollbar { display: none; }

  .nav-item {
    min-height: 40px;
    padding: 0 14px;
  }

  .main { padding: 30px 18px 64px; }
}

@media (max-width: 420px) {
  .header-inner { padding-inline: 14px; }
  .brand-copy small { display: none; }
  .nav { margin-inline: -2px; }
  .main { padding-inline: 14px; }
}
</style>
