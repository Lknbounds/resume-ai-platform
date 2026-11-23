<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个组件是什么】
 *  返回按钮。★ 用在所有"详情页"上，让用户随时能退回上一层。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么需要一个组件而不是每个页面写一遍？】
 *
 *  1. 智能返回的逻辑不简单（见下面的 goBack），写 3 遍容易不一致
 *  2. 样式统一：箭头 + 文字的间距、hover 效果、字号
 *  3. 以后想改交互（比如加"返回列表"的下拉），只改一处
 *
 *  【★★ 为什么用 router.back() 而不是固定跳某个路径？】
 *
 *  场景：用户在社区列表筛选了"Java + 3-5 年"，点进第 2 页的第 3 个帖子，
 *        然后点"返回"。
 *
 *    ❌ 固定 router.push('/community')
 *       → 回到列表第 1 页，筛选条件全没了，用户还得重新筛一遍
 *
 *    ✅ router.back()
 *       → 回到刚才那一页、那个位置、那个筛选状态（浏览器的历史记录里存着）
 *
 *  ★ 这是"尊重用户的操作路径"——他会觉得"系统记得我刚才在哪"。
 *
 *  【★★ 为什么还要判断 window.history.state?.back？】
 *
 *  ★ 如果是【直接打开链接】（比如别人分享的帖子链接、从书签打开），
 *    浏览器历史里没有"上一页"，此时 router.back() 会：
 *      - 要么什么都不做（用户点了没反应）
 *      - 要么跳出本站（用户体验很糟）
 *
 *  ★ 所以先检查历史：
 *      有上一页  → router.back()   （回到用户来的地方）
 *      没有上一页 → router.push(fallback)  （回到一个有意义的默认页）
 *
 *  ★ Vue Router 4 会在 history.state.back 里记录上一个路由。
 *    首次直接访问时它是 null。
 *
 *  【★★★ 还有第三种情况：上一页"不是这个页面的父级"】
 *
 *  场景：用户在【分析报告页】点"发布到社区"，发布成功后跳到帖子详情。
 *        此时历史是：/analysis/5 → /community/9
 *
 *    ❌ 直接 router.back()
 *       → 退回分析报告页，用户还是看不到社区列表
 *         （这就是最初"发布后一直在那个界面"的问题）
 *
 *    ✅ 判断"上一页是不是本页的父级路径"：
 *         是   → back()     保留用户的筛选/滚动位置
 *         不是 → push(to)   直接去列表
 *
 *  ★ 这样两种路径都对：
 *       列表 → 帖子详情        返回 → 列表（保留筛选）✓
 *       报告页 → 帖子详情      返回 → 列表（直接跳过去）✓
 */
import { useRouter } from 'vue-router'

const props = defineProps({
  /**
   * 兜底路径。★ 没有历史可回、或上一页不是父级时跳这里。
   *
   * 例：
   *     帖子详情  → /community
   *     分析报告  → /analysis/new
   *     简历详情  → /resumes
   */
  to: { type: String, default: '/' },

  /** 按钮文字。默认"返回"，可改成"返回列表"等 */
  label: { type: String, default: '返回' }
})

const router = useRouter()

function goBack() {
  /*
   * ★ window.history.state?.back 是 Vue Router 4 写入的。
   *   可选链 ?. 是因为某些环境（比如服务端渲染、初始状态）它可能是 undefined。
   */
  const prev = window.history.state?.back

  if (typeof prev === 'string' && isRelated(prev, props.to)) {
    router.back()
  } else {
    router.push(props.to)
  }
}

/**
 * 判断上一页是不是本页的"父级路径"。
 *
 * 例（to = '/community'）：
 *      prev = '/community'         → true（同级）
 *      prev = '/community?page=2'  → true（带查询参数）
 *      prev = '/analysis/5'        → false（不相关）
 *
 * ★ 为什么要处理查询参数？
 *   Vue Router 的 back 可能是 '/community?position=Java&page=2'
 *   直接字符串比较会判 false，导致"明明从列表来的却 push 了一个新列表页"。
 */
function isRelated(prev, to) {
  const base = to.split('?')[0]
  const prevPath = prev.split('?')[0]
  return prevPath === base || prevPath.startsWith(base + '/')
}
</script>

<template>
  <button class="back-btn" type="button" @click="goBack">
    <span class="arrow">←</span>
    <span class="label">{{ label }}</span>
  </button>
</template>

<style scoped>
/*
 * ★ 为什么用原生 <button> 而不是 el-button？
 *   1. el-button 自带 padding / 边框 / 圆角，要覆盖一堆样式
 *   2. 返回按钮要"轻"—— 无边框、无背景，hover 才出现浅底
 *   3. <button> 天生支持键盘 Tab + Enter，可访问性更好
 */
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;

  padding: 4px 9px 4px 5px;
  margin-bottom: 14px;

  border: none;
  border-radius: 6px;
  background: transparent;

  font-size: 12.5px;
  font-family: inherit;
  font-weight: 500;
  color: var(--text-muted);

  cursor: pointer;
  transition: background 0.12s, color 0.12s;
}

.back-btn:hover {
  background: var(--fill);
  color: var(--text);
}

/*
 * ★ 箭头单独加过渡：
 *   hover 时向左移 1px，"暗示"可以后退。
 *   这是很小的细节，但让按钮感觉"活"。
 */
.arrow {
  font-size: 14px;
  line-height: 1;
  transition: transform 0.12s;
}

.back-btn:hover .arrow {
  transform: translateX(-2px);
}

.label {
  line-height: 1;
}


/* 方案 C：清晰的键盘焦点与移动端触控尺寸 */
.back-btn { min-height: 36px; padding: 7px 11px 7px 7px; margin-bottom: 18px; }
.back-btn:focus-visible { outline: 3px solid rgba(238, 106, 75, 0.34); outline-offset: 2px; }
@media (max-width: 720px) { .back-btn { min-height: 44px; } }

</style>
