<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个组件是什么】
 *  "功能尚未实现"的占位页。
 *
 *  ★ 为什么需要它？
 *    步骤 4 只做了登录功能，但导航栏里已经有"我的简历""岗位 JD""社区"等入口。
 *    如果不加占位页，用户点了会 404 → 被路由守卫重定向回首页，
 *    看起来像"点了没反应"，很困惑。
 *
 *    加一个占位页明确告知"这个功能在步骤 X 实现"，
 *    既诚实又能验证导航是通的。
 *
 *  ★ 等对应步骤做完后，删掉占位页、换成真实页面即可。
 * ═══════════════════════════════════════════════════════════════════════
 */
defineProps({
  /** 功能名称 */
  title: { type: String, default: '功能' },
  /** 在哪一步实现 */
  step: { type: String, default: '' },
  /** 这个功能做什么 */
  desc: { type: String, default: '' }
})
</script>

<template>
  <el-card shadow="never">
    <el-empty :description="`${title} 尚未实现`">
      <template #image>
        <el-icon :size="64" color="var(--text-faint)"><Tools /></el-icon>
      </template>

      <template #description>
        <p class="title">{{ title }}</p>
        <p v-if="step" class="step">将在「{{ step }}」中实现</p>
        <p v-if="desc" class="desc">{{ desc }}</p>
      </template>

      <el-button @click="$router.push('/')">返回首页</el-button>
    </el-empty>
  </el-card>
</template>

<style scoped>
.title {
  font-size: 16px;
  color: #303133;
  margin: 0 0 8px;
}

.step {
  font-size: 13px;
  color: var(--accent-deep);
  margin: 0 0 8px;
}

.desc {
  font-size: 13px;
  color: var(--text-muted);
  max-width: 420px;
  line-height: 1.7;
  margin: 0;
}


/* 方案 C 空页面 */
:deep(.el-card) { border-radius: 22px; background: rgba(255, 252, 247, 0.7); }
:deep(.el-card__body) { padding: 28px; }
:deep(.el-empty__image) { color: var(--text-faint) !important; }
.title { font-family: var(--font-serif); font-size: 24px; color: var(--text); }
.step { color: var(--accent-deep); font-family: var(--font-mono); font-size: 10px; letter-spacing: 0.08em; }
.desc { color: var(--text-muted); }

</style>
