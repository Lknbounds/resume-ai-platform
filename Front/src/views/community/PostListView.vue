<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  社区列表。★ 浏览别人发布的简历分析结果。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 筛选 + 搜索 + 排序】
 *
 *      岗位下拉   → 等值筛选
 *      经验区间   → expMin / expMax
 *      关键词     → 搜索标题和正文
 *      最新/热度  → sort
 *
 *  ★ 为什么岗位用下拉而不是输入框？
 *    后端是等值匹配（能用索引）。输入框会让用户以为支持模糊搜索，
 *    结果搜不到就困惑。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { postApi } from '@/api/community'

const router = useRouter()
const route = useRoute()

const list = ref([])
const total = ref(0)
const loading = ref(true)
const page = ref(1)
const size = ref(10)

/** 筛选条件 */
const filters = ref({
  position: '',
  expRange: null,
  keyword: '',
  sort: 'new'
})

/** 经验区间选项 */
const expOptions = [
  { label: '应届', value: [0, 0] },
  { label: '1-3 年', value: [1, 3] },
  { label: '3-5 年', value: [3, 5] },
  { label: '5 年以上', value: [5, 50] }
]

let searchTimer = null

/**
 * ★ 初始化顺序很重要：
 *   先 initFromQuery() 把 URL 里的筛选读进内存，
 *   再 load() 才会按正确的条件查询。
 */
onMounted(() => {
  initFromQuery()
  load()
})

async function load() {
  loading.value = true
  try {
    const f = filters.value
    const params = { page: page.value, size: size.value, sort: f.sort }

    if (f.position) params.position = f.position
    if (f.expRange) {
      params.expMin = f.expRange[0]
      params.expMax = f.expRange[1]
    }
    if (f.keyword && f.keyword.trim()) params.keyword = f.keyword.trim()

    const data = await postApi.list(params)
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

/** 关键词防抖 */
function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    syncQuery()      // ★ 同步到 URL，返回时能恢复筛选
    load()
  }, 300)
}

/** 筛选变化立即重查 */
function onFilterChange() {
  page.value = 1
  syncQuery()        // ★ 同步到 URL
  load()
}

function onPage(p) {
  page.value = p
  syncQuery()        // ★ 页码也进 URL
  load()
}

/** 分数颜色 */
function scoreColor(score) {
  const s = Number(score)
  if (s >= 85) return 'var(--success)'
  if (s >= 70) return 'var(--info)'
  if (s >= 55) return 'var(--warning)'
  return 'var(--danger)'
}

/** 相对时间 */
function relativeTime(ts) {
  if (!ts) return ''
  const d = new Date(ts.replace(' ', 'T') + 'Z')
  const diff = Date.now() - d.getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const day = Math.floor(h / 24)
  if (day < 30) return `${day} 天前`
  return ts.slice(0, 10)
}

/** 投票 */
async function vote(post, type) {
  try {
    const r = await postApi.vote(post.id, type)
    post.upCount = r.upCount
    post.downCount = r.downCount
    post.myVote = r.myVote
  } catch {
    // 错误已由拦截器提示
  }
}

/** 有筛选条件吗（决定是否显示"清除"） */
const hasFilter = computed(() => {
  const f = filters.value
  return !!(f.position || f.expRange || (f.keyword && f.keyword.trim()))
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【★ 把筛选条件同步到 URL】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 【★★ 为什么必须同步？】
 *
 *  用户筛选"Java + 3-5 年"，点进一个帖子，再点"返回"。
 *
 *   ❌ 不同步 URL
 *      浏览器历史里存的是 /community（没有筛选参数）
 *      → 返回后组件重新挂载 → 筛选条件重置为默认
 *      → 用户得重新筛一遍，会觉得"系统没记住我"
 *
 *   ✅ 同步 URL
 *      历史里存的是 /community?position=Java&expMin=3&expMax=5
 *      → 返回时 query 被还原 → 筛选条件恢复
 *
 *  ★ 附带好处：这个 URL 可以直接分享给别人。
 *
 * 【★ 为什么用 router.replace 而不是 push？】
 *  改筛选不应该产生新的历史记录 ——
 *  否则用户按"后退"会在不同的筛选状态之间来回跳，
 *  而不是退出列表页。
 */
function syncQuery() {
  const f = filters.value
  const query = {}

  if (f.position) query.position = f.position
  if (f.expRange) {
    query.expMin = String(f.expRange[0])
    query.expMax = String(f.expRange[1])
  }
  if (f.keyword && f.keyword.trim()) query.keyword = f.keyword.trim()
  if (f.sort && f.sort !== 'new') query.sort = f.sort
  if (page.value > 1) query.page = String(page.value)

  router.replace({ path: '/community', query })
}

/**
 * 从 URL 还原筛选条件（页面初始化时调用）。
 */
function initFromQuery() {
  const q = route.query

  page.value = Number(q.page) > 0 ? Number(q.page) : 1

  filters.value = {
    position: q.position || '',
    expRange: (q.expMin != null && q.expMin !== '')
            ? [Number(q.expMin), Number(q.expMax ?? 50)]
            : null,
    keyword: q.keyword || '',
    sort: q.sort === 'hot' ? 'hot' : 'new'
  }
}

/**
 * 排名角标的样式类。
 *
 * ★ 前三名用金银铜色 —— 一眼能看出"前三"。
 *   第 4 名往后用灰底，不抢眼。
 *
 * ★ 为什么不给前三名加图标？
 *   列表里已经有很多视觉元素（分数、标签、头像），
 *   再加奖牌图标会很吵。颜色足够。
 */
function rankClass(rank) {
  if (rank === 1) return 'rank-gold'
  if (rank === 2) return 'rank-silver'
  if (rank === 3) return 'rank-bronze'
  return 'rank-normal'
}

function clearFilters() {
  filters.value = { position: '', expRange: null, keyword: '', sort: filters.value.sort }
  page.value = 1
  syncQuery()
  load()
}
</script>

<template>
  <DefaultLayout>
    <!-- ═══════════ 页头 ═══════════ -->
    <div class="page-head">
      <div>
        <h1 class="page-title">社区</h1>
        <p class="page-sub">看看别人的简历怎么改，也给点建议</p>
      </div>
    </div>

    <!-- ═══════════ 筛选栏 ═══════════ -->
    <div class="filters">
      <el-input
        v-model="filters.keyword"
        placeholder="搜索标题或内容"
        clearable
        class="search"
        @input="onSearchInput"
        @clear="onFilterChange"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <el-input
        v-model="filters.position"
        placeholder="目标岗位"
        clearable
        class="filter-input"
        @change="onFilterChange"
        @clear="onFilterChange"
      />

      <el-select
        v-model="filters.expRange"
        placeholder="经验年限"
        clearable
        class="filter-input"
        @change="onFilterChange"
      >
        <el-option
          v-for="o in expOptions"
          :key="o.label"
          :label="o.label"
          :value="o.value"
        />
      </el-select>

      <el-radio-group v-model="filters.sort" @change="onFilterChange">
        <el-radio-button value="new">最新</el-radio-button>
        <el-radio-button value="hot">热度</el-radio-button>
      </el-radio-group>

      <el-button v-if="hasFilter" text size="small" @click="clearFilters">
        清除筛选
      </el-button>

      <!--
        ★ 热度模式下提示"筛选会回退到数据库排序"
          —— 让用户理解为什么筛选后的顺序和纯热度榜不一样。
        ★ 这个提示只在 sort=hot 且有筛选时出现，避免平时多余的文字。
      -->
      <span v-if="filters.sort === 'hot' && hasFilter" class="hot-hint">
        筛选状态下按数据库热度分排序
      </span>
    </div>

    <el-skeleton v-if="loading" :rows="5" animated />

    <!-- ═══════════ 空状态 ═══════════ -->
    <div v-else-if="list.length === 0" class="empty">
      <div class="empty-icon">简</div>
      <p class="empty-title">{{ hasFilter ? '没有匹配的帖子' : '社区还很安静' }}</p>
      <p class="empty-desc">
        {{ hasFilter ? '试试换个筛选条件' : '分析完简历后，可以一键发布到这里求建议' }}
      </p>
      <el-button v-if="hasFilter" @click="clearFilters">清除筛选</el-button>
    </div>

    <!-- ═══════════ 帖子列表 ═══════════ -->
    <div v-else class="list">
      <article
        v-for="(p, index) in list"
        :key="p.id"
        class="card"
        @click="router.push(`/community/${p.id}`)"
      >
        <div class="card-top">
          <div class="card-main">
            <div class="card-title-row">
              <!--
                ★ 热度模式下显示排名角标（1/2/3 用金银铜色）。
                  ★ 为什么只在热度模式显示？
                    最新排序里的第 1 名不代表"最热门"，显示排名会误导。
              -->
              <span
                v-if="filters.sort === 'hot' && !hasFilter"
                class="rank"
                :class="rankClass((page - 1) * size + index + 1)"
              >
                {{ (page - 1) * size + index + 1 }}
              </span>

              <h3 class="card-title">{{ p.title }}</h3>
              <span
                class="score"
                :style="{ color: scoreColor(p.overallScore) }"
              >{{ Number(p.overallScore).toFixed(0) }}</span>
            </div>

            <div class="card-meta">
              <!-- 匿名作者只显示"匿名用户"，没有 userId -->
              <span class="author">
                <span class="avatar">{{ p.author.nickname.slice(0, 1) }}</span>
                {{ p.author.nickname }}
              </span>
              <span class="dot">·</span>
              <span v-if="p.targetPosition" class="tag">{{ p.targetPosition }}</span>
              <span v-if="p.targetPosition" class="dot">·</span>
              <span>{{ p.yearsOfExperience }} 年</span>
              <span class="dot">·</span>
              <span>{{ relativeTime(p.createdAt) }}</span>
            </div>

            <p v-if="p.content" class="card-content">{{ p.content }}</p>
          </div>
        </div>

        <!-- 底部：投票 + 评论数（@click.stop 防止触发卡片跳转） -->
        <div class="card-bottom" @click.stop>
          <button
            class="vote-btn"
            :class="{ active: p.myVote === 'UP' }"
            @click="vote(p, 'UP')"
          >
            <span class="vote-icon">▲</span>
            {{ p.upCount }}
          </button>

          <button
            class="vote-btn"
            :class="{ active: p.myVote === 'DOWN' }"
            @click="vote(p, 'DOWN')"
          >
            <span class="vote-icon">▼</span>
            {{ p.downCount }}
          </button>

          <span class="stat">
            <span class="stat-icon">💬</span>
            {{ p.commentCount }}
          </span>

          <span class="stat">
            <span class="stat-icon">👁</span>
            {{ p.viewCount }}
          </span>

          <span v-if="p.isOwner" class="owner-badge">我的</span>
        </div>
      </article>
    </div>

    <el-pagination
      v-if="total > size"
      class="pager"
      background
      layout="total, prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPage"
    />
  </DefaultLayout>
</template>

<style scoped>
.page-head {
  margin-bottom: 20px;
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text);
  margin: 0 0 6px;
}

.page-sub {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0;
}

/* ─────────── 筛选栏 ─────────── */

.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.search {
  width: 220px;
}

.filter-input {
  width: 150px;
}

/*
 * ★ 热度筛选的说明文字：
 *   小号、灰色、不抢眼 —— 只在需要时出现，
 *   目的是解释"为什么顺序和纯热度榜不同"，不是装饰。
 */
.hot-hint {
  font-size: 11px;
  color: var(--text-faint);
}

/* ─────────── 空状态 ─────────── */

.empty {
  text-align: center;
  padding: 80px 0;
}

.empty-icon {
  width: 52px;
  height: 52px;
  margin: 0 auto 20px;
  border-radius: 15px;
  background: var(--gradient-brand);
  box-shadow: 0 6px 20px rgba(41, 163, 153, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 22px;
  font-weight: 700;
}

.empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
  margin: 0 0 6px;
}

.empty-desc {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0 0 22px;
}

/* ─────────── 帖子卡片 ─────────── */

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.card {
  background: var(--surface);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  padding: 16px 18px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.15s;
}

.card:hover {
  border-color: rgba(41, 163, 153, 0.35);
  box-shadow: 0 4px 16px rgba(18, 23, 33, 0.07);
  transform: translateY(-1px);
}

.card-title-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 8px;
}

/* ─────────── 热度排名角标 ─────────── */

/*
 * ★ 小尺寸方块（18px），数字居中。
 *   不做得太大 —— 它是辅助信息，不是主角。
 */
.rank {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;

  width: 18px;
  height: 18px;
  border-radius: 5px;

  font-size: 11px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;

  margin-top: 1px;
}

/* 前三名：金银铜 */
.rank-gold {
  background: linear-gradient(135deg, #fbbf24, #f59e0b);
  color: #fff;
}

.rank-silver {
  background: linear-gradient(135deg, #cbd5e1, #94a3b8);
  color: #fff;
}

.rank-bronze {
  background: linear-gradient(135deg, #d6a679, #b45309);
  color: #fff;
}

/* 第 4 名往后：灰底，不抢眼 */
.rank-normal {
  background: var(--fill);
  color: var(--text-faint);
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  margin: 0;
  line-height: 1.5;
}

/*
 * ★ 分数放在标题右侧，大号 + 语义色。
 *   这是列表里最有信息量的元素 —— 用户扫一眼就知道这份简历什么水平。
 */
.score {
  flex-shrink: 0;
  font-size: 20px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
  line-height: 1.1;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--text-faint);
  flex-wrap: wrap;
}

.author {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-muted);
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 10px;
  font-weight: 600;
}

.tag {
  color: var(--brand-deep);
  background: var(--brand-soft);
  padding: 1px 6px;
  border-radius: 4px;
}

.dot {
  color: var(--line);
}

.card-content {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-muted);
  margin: 10px 0 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ─────────── 卡片底部 ─────────── */

.card-bottom {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}

/*
 * ★ 投票按钮做成"文字 + 图标"的轻量样式，
 *   而不是实心按钮 —— 实心按钮在列表里会很重。
 *   选中时用品牌色文字区分。
 */
.vote-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;

  padding: 3px 8px;
  border: none;
  border-radius: 5px;
  background: transparent;

  font-size: 12px;
  font-family: inherit;
  color: var(--text-faint);
  cursor: pointer;

  transition: background 0.12s, color 0.12s;
}

.vote-btn:hover {
  background: var(--fill);
  color: var(--text-muted);
}

.vote-btn.active {
  color: var(--brand-deep);
  background: var(--brand-soft);
  font-weight: 600;
}

.vote-icon {
  font-size: 9px;
}

.stat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-faint);
}

.stat-icon {
  font-size: 11px;
  opacity: 0.7;
}

.owner-badge {
  margin-left: auto;
  font-size: 10px;
  color: var(--brand-deep);
  background: var(--brand-soft);
  padding: 2px 7px;
  border-radius: 999px;
  font-weight: 500;
}

.pager {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 760px) {
  .filters {
    gap: 8px;
  }

  .search,
  .filter-input {
    width: 100%;
  }
}
</style>
