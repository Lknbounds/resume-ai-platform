<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import BackButton from '@/components/BackButton.vue'
import { postApi } from '@/api/community'

const router = useRouter()
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(8)
const loading = ref(true)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const data = await postApi.mine({ page: page.value, size: size.value })
    list.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function onPage(value) {
  page.value = value
  load()
}

function openPost(id) {
  router.push(`/community/${id}`)
}

function scoreColor(score) {
  const value = Number(score)
  if (value >= 85) return 'var(--success)'
  if (value >= 70) return 'var(--info)'
  if (value >= 55) return 'var(--warning)'
  return 'var(--danger)'
}

function formatDate(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '-'
}
</script>

<template>
  <DefaultLayout>
    <BackButton to="/" label="返回首页" />

    <div class="studio-page-head my-posts-head">
      <div class="studio-page-copy">
        <p class="studio-eyebrow">MY COMMUNITY NOTES</p>
        <h1 class="studio-page-title">我的帖子</h1>
        <p class="studio-page-sub">集中查看你发布过的简历讨论，继续跟进社区建议。</p>
      </div>
      <el-button class="community-btn" @click="router.push('/community')">去社区看看</el-button>
    </div>

    <div class="summary-strip">
      <div class="summary-main">
        <span class="summary-number">{{ total }}</span>
        <span class="summary-label">篇公开帖子</span>
      </div>
      <p>帖子中的建议被采纳后，会回写到对应简历并生成新的分析结果。</p>
    </div>

    <el-skeleton v-if="loading" :rows="8" animated />

    <section v-else-if="list.length === 0" class="studio-paper empty-state">
      <span class="empty-mark" aria-hidden="true">简</span>
      <h2>还没有发布过帖子</h2>
      <p>完成一次简历分析后，可以把结果发到社区，邀请其他用户给出具体建议。</p>
      <div class="empty-actions">
        <el-button @click="router.push('/community')">浏览社区</el-button>
        <el-button type="primary" @click="router.push('/analysis/new')">开始分析</el-button>
      </div>
    </section>

    <template v-else>
      <div class="post-grid">
        <article
          v-for="item in list"
          :key="item.id"
          class="studio-paper post-card"
          tabindex="0"
          role="link"
          @click="openPost(item.id)"
          @keydown.enter="openPost(item.id)"
        >
          <header class="card-head">
            <div class="card-copy">
              <div class="card-tags">
                <span class="status-tag">已发布</span>
                <span v-if="item.isAnonymous" class="quiet-tag">匿名发布</span>
              </div>
              <h2>{{ item.title }}</h2>
            </div>
            <div class="score-block" :style="{ color: scoreColor(item.overallScore) }">
              <strong>{{ Number(item.overallScore).toFixed(0) }}</strong>
              <small>匹配分</small>
            </div>
          </header>

          <p v-if="item.content" class="card-content">{{ item.content }}</p>
          <p v-else class="card-content muted">这篇帖子没有补充说明。</p>

          <div class="card-meta">
            <span v-if="item.targetPosition" class="position">{{ item.targetPosition }}</span>
            <span>{{ item.yearsOfExperience }} 年经验</span>
            <span class="dot">·</span>
            <span>{{ formatDate(item.createdAt) }}</span>
          </div>

          <footer class="card-foot">
            <span><el-icon><ChatDotRound /></el-icon>{{ item.commentCount }} 条讨论</span>
            <span><el-icon><View /></el-icon>{{ item.viewCount }} 次浏览</span>
            <span class="open-link">查看详情 <span aria-hidden="true">→</span></span>
          </footer>
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
    </template>
  </DefaultLayout>
</template>

<style scoped>
.my-posts-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.community-btn { flex-shrink: 0; }

.summary-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  margin: 20px 0 18px;
  padding: 16px 20px;
  border: 1px solid var(--line-soft);
  border-radius: 16px;
  background: rgba(255, 252, 247, 0.72);
  box-shadow: var(--shadow-sm);
}

.summary-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-shrink: 0;
}

.summary-number {
  color: var(--brand-deep);
  font-family: var(--font-serif);
  font-size: 30px;
  font-weight: 700;
  line-height: 1;
}

.summary-label {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
}

.summary-strip p {
  max-width: 640px;
  margin: 0;
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.65;
  text-align: right;
}

.post-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.post-card {
  min-height: 248px;
  padding: 22px;
  cursor: pointer;
  transition: transform 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease;
}

.post-card:hover,
.post-card:focus-visible {
  transform: translateY(-2px);
  border-color: rgba(31, 75, 62, 0.24);
  box-shadow: 0 12px 30px rgba(31, 45, 39, 0.09);
  outline: none;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.card-copy { min-width: 0; }

.card-tags {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 11px;
}

.status-tag,
.quiet-tag {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.status-tag {
  color: var(--brand-deep);
  background: var(--brand-soft);
}

.quiet-tag {
  color: var(--text-faint);
  border: 1px solid var(--line-soft);
}

.card-copy h2 {
  margin: 0;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 21px;
  line-height: 1.45;
  letter-spacing: -0.02em;
}

.score-block {
  display: grid;
  justify-items: center;
  flex-shrink: 0;
  min-width: 66px;
  padding: 8px 10px;
  border-radius: 14px;
  background: var(--surface-deep);
}

.score-block strong {
  font-family: var(--font-serif);
  font-size: 27px;
  line-height: 1;
}

.score-block small {
  margin-top: 4px;
  color: var(--text-faint);
  font-size: 9px;
}

.card-content {
  display: -webkit-box;
  min-height: 47px;
  margin: 16px 0 14px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.75;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.card-content.muted { color: var(--text-faint); }

.card-meta {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  color: var(--text-faint);
  font-size: 10.5px;
}

.position {
  color: var(--accent-deep);
  font-weight: 600;
}

.dot { color: var(--line); }

.card-foot {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--line-soft);
  color: var(--text-faint);
  font-size: 10.5px;
}

.card-foot span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.open-link {
  margin-left: auto;
  color: var(--brand-deep);
  font-weight: 600;
}

.empty-state {
  padding: 62px 28px;
  text-align: center;
}

.empty-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 54px;
  height: 54px;
  margin-bottom: 18px;
  border-radius: 17px;
  background: var(--gradient-brand);
  box-shadow: 0 8px 22px rgba(31, 75, 62, 0.2);
  color: #fff;
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 700;
}

.empty-state h2 {
  margin: 0 0 8px;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 24px;
}

.empty-state p {
  max-width: 520px;
  margin: 0 auto;
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.75;
}

.empty-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
  margin-top: 22px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 22px;
}
</style>
