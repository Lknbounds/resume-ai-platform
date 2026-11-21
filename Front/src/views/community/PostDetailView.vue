<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  帖子详情。★ 左侧简历 + 报告快照，右侧评论区。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      进入 → GET /api/posts/{id}          → 帖子 + 简历快照 + 报告快照
 *           → GET /api/posts/{id}/comments → 两级评论树
 *              ↓
 *      用户评论 → POST …/comments（★ 走个人信息审核）
 *              ↓
 *      用户投票 → POST …/votes
 *              ↓
 *      作者采纳 → POST /api/comments/{id}/adopt
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import BackButton from '@/components/BackButton.vue'
import { postApi, commentApi } from '@/api/community'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const post = ref(null)
const comments = ref([])
const loading = ref(true)
const commentLoading = ref(false)

/** 评论筛选 */
const commentFilter = ref({ section: '', sort: 'newest' })

/** 新评论输入 */
const newComment = ref('')
const submitting = ref(false)

/** 回复目标（null = 发顶层评论） */
const replyTo = ref(null)

const sectionOptions = [
  { label: '全部', value: '' },
  { label: '技能', value: 'SKILLS' },
  { label: '工作经历', value: 'EXPERIENCE' },
  { label: '项目', value: 'PROJECT' },
  { label: '教育', value: 'EDUCATION' },
  { label: '自我评价', value: 'SUMMARY' },
  { label: '其他', value: 'OTHER' }
]

onMounted(async () => {
  try {
    post.value = await postApi.detail(route.params.id)
    await loadComments()
  } finally {
    loading.value = false
  }
})

async function loadComments() {
  commentLoading.value = true
  try {
    const params = { sort: commentFilter.value.sort }
    if (commentFilter.value.section) params.section = commentFilter.value.section
    comments.value = await commentApi.list(route.params.id, params)
  } finally {
    commentLoading.value = false
  }
}

function onFilterChange() {
  loadComments()
}

/** 点"回复" → 设置回复目标 */
function startReply(comment) {
  /*
   * ★ 只允许回复【顶层评论】。
   *   如果用户点的是回复（已经是二级），就把它归到它的 rootId 下。
   *   这样界面上不会出现"第三级"。
   */
  replyTo.value = comment.parentId ? { id: comment.rootId, nickname: comment.author.nickname } : comment
}

function cancelReply() {
  replyTo.value = null
}

/** 发表评论 */
async function submitComment() {
  if (!newComment.value.trim()) {
    return ElMessage.warning('请输入内容')
  }

  submitting.value = true
  try {
    await commentApi.create(route.params.id, {
      parentId: replyTo.value?.id || null,
      section: replyTo.value ? null : (commentFilter.value.section || 'OTHER'),
      content: newComment.value.trim()
    })

    newComment.value = ''
    replyTo.value = null
    await loadComments()

    // 评论数 +1（本地更新，避免重新拉帖子）
    if (post.value) post.value.commentCount++

    ElMessage.success('已发表')
  } catch (e) {
    /*
     * ★ 43002（含个人信息）需要特殊处理：
     *   拦截器已 reject，这里给出明确提示。
     *   ★ 不清空输入框 —— 用户改一下就能重发。
     */
    if (e?.code === 43002) {
      ElMessage.error('评论里有手机号/邮箱等个人信息，请修改后再发')
    }
  } finally {
    submitting.value = false
  }
}

/** 帖子投票 */
async function votePost(type) {
  try {
    const r = await postApi.vote(post.value.id, type)
    post.value.upCount = r.upCount
    post.value.downCount = r.downCount
    post.value.myVote = r.myVote
  } catch { /* 拦截器已提示 */ }
}

/** 评论投票 */
async function voteComment(comment, type) {
  try {
    const r = await commentApi.vote(comment.id, type)
    comment.likeCount = r.likeCount
    comment.usefulCount = r.usefulCount
    comment.myLiked = r.myLiked
    comment.myUseful = r.myUseful
  } catch { /* 拦截器已提示 */ }
}

/** 删除评论 */
async function removeComment(comment) {
  try {
    await ElMessageBox.confirm('确定删除这条评论？', '确认', { type: 'warning' })
  } catch {
    return
  }
  await commentApi.remove(comment.id)
  ElMessage.success('已删除')
  await loadComments()
  if (post.value) post.value.commentCount = Math.max(0, post.value.commentCount - 1)
}

/** 删除帖子 */
async function removePost() {
  try {
    await ElMessageBox.confirm('删除后帖子不再展示，确定继续？', '确认删除', { type: 'warning' })
  } catch {
    return
  }
  await postApi.remove(post.value.id)
  ElMessage.success('已删除')
  router.push('/community')
}

/** 分数颜色 */
function scoreColor(score) {
  const s = Number(score)
  if (s >= 85) return 'var(--success)'
  if (s >= 70) return 'var(--info)'
  if (s >= 55) return 'var(--warning)'
  return 'var(--danger)'
}

function barColor(score) {
  return scoreColor(score)
}

/** 相对时间 */
function relativeTime(ts) {
  if (!ts) return ''
  const d = new Date(ts.replace(' ', 'T') + 'Z')
  const min = Math.floor((Date.now() - d.getTime()) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  return `${Math.floor(h / 24)} 天前`
}

const severityLabel = { HIGH: '优先改', MEDIUM: '建议改', LOW: '可选' }
function severityType(sev) {
  return { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }[sev] || 'info'
}

/** 是否登录（未登录不能评论/投票） */
const canInteract = computed(() => user.isLoggedIn)
</script>

<template>
  <DefaultLayout>
    <!--
      ★ 返回按钮放在最顶部，数据加载中也显示 ——
        这样用户点了帖子、内容还没出来时也能马上退回去。
    -->
    <BackButton to="/community" label="返回社区" />

    <el-skeleton v-if="loading" :rows="10" animated />

    <template v-else-if="post">
      <!-- ═══════════ 帖子头部 ═══════════ -->
      <div class="post-head">
        <div class="head-main">
          <h1 class="post-title">{{ post.title }}</h1>

          <div class="post-meta">
            <span class="avatar">{{ post.author.nickname.slice(0, 1) }}</span>
            <span>{{ post.author.nickname }}</span>
            <span class="dot">·</span>
            <span>{{ relativeTime(post.createdAt) }}</span>
            <template v-if="post.targetPosition">
              <span class="dot">·</span>
              <span class="tag">{{ post.targetPosition }}</span>
            </template>
            <span v-if="post.isAnonymous" class="anon-badge">匿名发布</span>
          </div>

          <p v-if="post.content" class="post-content">{{ post.content }}</p>
        </div>

        <div class="head-side">
          <div class="big-score" :style="{ color: scoreColor(post.overallScore) }">
            {{ Number(post.overallScore).toFixed(0) }}
          </div>
          <div class="score-label">匹配分</div>
        </div>
      </div>

      <!-- ═══════════ 投票栏 ═══════════ -->
      <div class="vote-bar">
        <el-button
          :type="post.myVote === 'UP' ? 'primary' : 'default'"
          size="small"
          :disabled="!canInteract"
          @click="votePost('UP')"
        >
          ▲ 有帮助 {{ post.upCount }}
        </el-button>

        <el-button
          size="small"
          :disabled="!canInteract"
          @click="votePost('DOWN')"
        >
          ▼ {{ post.downCount }}
        </el-button>

        <span class="stat">💬 {{ post.commentCount }}</span>
        <span class="stat">👁 {{ post.viewCount }}</span>

        <el-button
          v-if="post.isOwner"
          text
          size="small"
          class="del"
          @click="removePost"
        >
          删除帖子
        </el-button>
      </div>

      <!-- ═══════════ 两栏 ═══════════ -->
      <el-row :gutter="16" class="body">
        <!-- 左：简历 + 报告 -->
        <el-col :span="14">
          <!-- 简历正文 -->
          <section class="card">
            <header class="card-head">
              <span class="card-title">简历内容</span>
              <span class="card-hint">已通过个人信息审核</span>
            </header>
            <div class="pre-wrap resume-text">{{ post.resumeContent }}</div>
          </section>

          <!-- 报告快照 -->
          <section v-if="post.report" class="card">
            <header class="card-head">
              <span class="card-title">分析报告</span>
              <el-tag size="small" effect="plain">{{ post.report.grade }}</el-tag>
            </header>

            <!-- 维度 -->
            <div class="dims">
              <div v-for="d in post.report.dimensions" :key="d.key" class="dim">
                <div class="dim-top">
                  <span>{{ d.name }}</span>
                  <span class="dim-score" :style="{ color: barColor(d.score) }">
                    {{ d.applicable ? Number(d.score).toFixed(1) : '—' }}
                  </span>
                </div>
                <div v-if="d.applicable" class="dim-bar">
                  <div class="dim-fill"
                       :style="{ width: d.score + '%', background: barColor(d.score) }" />
                </div>
              </div>
            </div>

            <!-- 关键词 -->
            <div class="kw-section">
              <div class="kw-line">
                <span class="kw-label">已匹配</span>
                <span v-for="k in post.report.hits" :key="k.name" class="tag tag-ok">{{ k.name }}</span>
                <span v-if="!post.report.hits?.length" class="kw-empty">无</span>
              </div>
              <div class="kw-line">
                <span class="kw-label">缺失</span>
                <span v-for="k in post.report.missing" :key="k.name" class="tag tag-miss">{{ k.name }}</span>
                <span v-if="!post.report.missing?.length" class="kw-empty">无</span>
              </div>
            </div>

            <!-- 建议 -->
            <div v-if="post.report.suggestions?.length" class="sugs">
              <p class="kw-label" style="margin-bottom: 8px">改进建议</p>
              <div v-for="(s, i) in post.report.suggestions" :key="i" class="sug">
                <el-tag size="small" :type="severityType(s.severity)" effect="plain">
                  {{ severityLabel[s.severity] }}
                </el-tag>
                <span class="sug-issue">{{ s.issue }}</span>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右：评论 -->
        <el-col :span="10">
          <section class="card comments-card">
            <header class="card-head">
              <span class="card-title">评论 {{ post.commentCount }}</span>
              <div class="comment-filters">
                <el-select
                  v-model="commentFilter.section"
                  size="small"
                  style="width: 96px"
                  @change="onFilterChange"
                >
                  <el-option
                    v-for="o in sectionOptions"
                    :key="o.value"
                    :label="o.label"
                    :value="o.value"
                  />
                </el-select>
                <el-select
                  v-model="commentFilter.sort"
                  size="small"
                  style="width: 90px"
                  @change="onFilterChange"
                >
                  <el-option label="最新" value="newest" />
                  <el-option label="有用优先" value="useful" />
                </el-select>
              </div>
            </header>

            <!-- 评论列表 -->
            <el-skeleton v-if="commentLoading" :rows="3" animated />

            <div v-else-if="comments.length === 0" class="empty-comments">
              还没有评论，来给点建议吧
            </div>

            <div v-else class="comment-list">
              <div v-for="c in comments" :key="c.id" class="comment">
                <div class="c-head">
                  <span class="avatar sm">{{ c.author.nickname.slice(0, 1) }}</span>
                  <span class="c-nick">{{ c.author.nickname }}</span>
                  <span class="c-section">{{ c.sectionLabel }}</span>
                  <el-tag v-if="c.adopted" size="small" type="success" effect="dark">
                    已采纳
                  </el-tag>
                  <span class="c-time">{{ relativeTime(c.createdAt) }}</span>
                </div>

                <p class="c-content">{{ c.content }}</p>

                <div class="c-actions">
                  <button
                    class="mini-btn"
                    :class="{ active: c.myLiked }"
                    :disabled="!canInteract"
                    @click="voteComment(c, 'UP')"
                  >
                    ▲ {{ c.likeCount }}
                  </button>
                  <button
                    class="mini-btn"
                    :class="{ active: c.myUseful }"
                    :disabled="!canInteract"
                    @click="voteComment(c, 'USEFUL')"
                  >
                    有用 {{ c.usefulCount }}
                  </button>
                  <button v-if="canInteract" class="mini-btn" @click="startReply(c)">回复</button>
                  <button
                    v-if="c.canDelete"
                    class="mini-btn danger"
                    @click="removeComment(c)"
                  >
                    删除
                  </button>
                </div>

                <!-- 二级：回复 -->
                <div v-if="c.replies?.length" class="replies">
                  <div v-for="r in c.replies" :key="r.id" class="reply">
                    <div class="c-head">
                      <span class="avatar sm">{{ r.author.nickname.slice(0, 1) }}</span>
                      <span class="c-nick">{{ r.author.nickname }}</span>
                      <span class="c-time">{{ relativeTime(r.createdAt) }}</span>
                    </div>
                    <p class="c-content">{{ r.content }}</p>
                    <div class="c-actions">
                      <button
                        class="mini-btn"
                        :class="{ active: r.myLiked }"
                        :disabled="!canInteract"
                        @click="voteComment(r, 'UP')"
                      >
                        ▲ {{ r.likeCount }}
                      </button>
                      <button
                        class="mini-btn"
                        :class="{ active: r.myUseful }"
                        :disabled="!canInteract"
                        @click="voteComment(r, 'USEFUL')"
                      >
                        有用 {{ r.usefulCount }}
                      </button>
                      <button
                        v-if="r.canDelete"
                        class="mini-btn danger"
                        @click="removeComment(r)"
                      >
                        删除
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 输入框 -->
            <div v-if="canInteract" class="composer">
              <div v-if="replyTo" class="reply-hint">
                回复 {{ replyTo.nickname }}
                <el-button link size="small" @click="cancelReply">取消</el-button>
              </div>

              <el-input
                v-model="newComment"
                type="textarea"
                :rows="3"
                :placeholder="replyTo ? `回复 ${replyTo.nickname}…` : '给点具体建议，比如「项目里加上 QPS」'"
                maxlength="1000"
                show-word-limit
              />

              <div class="composer-foot">
                <span class="composer-tip">发布前会自动检查是否含手机号、邮箱等</span>
                <el-button type="primary" size="small" :loading="submitting" @click="submitComment">
                  发表
                </el-button>
              </div>
            </div>

            <div v-else class="login-hint">
              <el-button link type="primary" @click="router.push('/login')">登录</el-button> 后可以评论和投票
            </div>
          </section>
        </el-col>
      </el-row>
    </template>
  </DefaultLayout>
</template>

<style scoped>
/* ─────────── 帖子头 ─────────── */

.post-head {
  display: flex;
  gap: 24px;
  padding-bottom: 16px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--line-soft);
}

.head-main {
  flex: 1;
  min-width: 0;
}

.post-title {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.45;
  letter-spacing: -0.02em;
  color: var(--text);
  margin: 0 0 10px;
}

.post-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-faint);
  flex-wrap: wrap;
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 11px;
  font-weight: 600;
}

.avatar.sm {
  width: 18px;
  height: 18px;
  font-size: 10px;
}

.dot { color: var(--line); }

.tag {
  color: var(--brand-deep);
  background: var(--brand-soft);
  padding: 1px 6px;
  border-radius: 4px;
}

.anon-badge {
  font-size: 10px;
  color: var(--text-faint);
  border: 1px solid var(--line);
  padding: 1px 6px;
  border-radius: 4px;
}

.post-content {
  font-size: 13px;
  line-height: 1.75;
  color: var(--text-muted);
  margin: 12px 0 0;
}

.head-side {
  flex-shrink: 0;
  text-align: center;
}

.big-score {
  font-size: 44px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.04em;
  font-variant-numeric: tabular-nums;
}

.score-label {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 4px;
}

/* ─────────── 投票栏 ─────────── */

.vote-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.stat {
  font-size: 12px;
  color: var(--text-faint);
}

.del {
  margin-left: auto;
  color: var(--danger);
}

/* ─────────── 两栏 ─────────── */

.body {
  align-items: flex-start;
}

.card {
  background: var(--surface);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  padding: 16px 18px;
  margin-bottom: 12px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.card-hint {
  font-size: 11px;
  color: var(--success);
}

.resume-text {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--text-muted);
  max-height: 520px;
  overflow-y: auto;
}

/* ─────────── 报告快照 ─────────── */

.dims {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}

.dim-top {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  margin-bottom: 5px;
}

.dim-score {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.dim-bar {
  height: 4px;
  background: var(--fill);
  border-radius: 999px;
  overflow: hidden;
}

.dim-fill {
  height: 100%;
  border-radius: 999px;
}

.kw-section {
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}

.kw-line {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.kw-label {
  flex-shrink: 0;
  width: 48px;
  font-size: 11px;
  color: var(--text-faint);
}

.tag-ok {
  background: rgba(22, 163, 74, 0.09);
  color: #15803d;
}

.tag-miss {
  background: rgba(220, 38, 38, 0.08);
  color: #b91c1c;
}

.tag-ok, .tag-miss {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 4px;
}

.kw-empty {
  font-size: 11px;
  color: var(--text-faint);
}

.sugs {
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}

.sug {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 6px 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}

.sug-issue {
  flex: 1;
}

/* ─────────── 评论 ─────────── */

.comments-card {
  position: sticky;
  top: 72px;
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
}

.comment-filters {
  display: flex;
  gap: 6px;
}

.comment-list {
  flex: 1;
  overflow-y: auto;
  margin-bottom: 12px;
}

.empty-comments {
  flex: 1;
  text-align: center;
  padding: 40px 0;
  font-size: 12px;
  color: var(--text-faint);
}

.comment {
  padding: 12px 0;
  border-bottom: 1px solid var(--line-soft);
}

.comment:last-child {
  border-bottom: none;
}

.c-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.c-nick {
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
}

.c-section {
  font-size: 10px;
  color: var(--brand-deep);
  background: var(--brand-soft);
  padding: 1px 5px;
  border-radius: 3px;
}

.c-time {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--text-faint);
}

.c-content {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-muted);
  margin: 0 0 6px;
  white-space: pre-wrap;
  word-break: break-word;
}

.c-actions {
  display: flex;
  gap: 4px;
}

/*
 * ★ 微型按钮：评论区的操作要"轻"，
 *   否则每条评论下面一排按钮会很吵。
 */
.mini-btn {
  padding: 2px 7px;
  border: none;
  border-radius: 4px;
  background: transparent;
  font-size: 11px;
  font-family: inherit;
  color: var(--text-faint);
  cursor: pointer;
  transition: background 0.12s, color 0.12s;
}

.mini-btn:hover:not(:disabled) {
  background: var(--fill);
  color: var(--text-muted);
}

.mini-btn.active {
  color: var(--brand-deep);
  background: var(--brand-soft);
  font-weight: 600;
}

.mini-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.mini-btn.danger:hover {
  color: var(--danger);
  background: rgba(220, 38, 38, 0.06);
}

/* 二级回复 */
.replies {
  margin-top: 10px;
  padding-left: 14px;
  border-left: 2px solid var(--line-soft);
}

.reply {
  padding: 8px 0;
}

.reply + .reply {
  border-top: 1px solid var(--line-soft);
}

/* ─────────── 输入框 ─────────── */

.composer {
  flex-shrink: 0;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}

.reply-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--brand-deep);
  margin-bottom: 8px;
}

.composer-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  gap: 8px;
}

.composer-tip {
  font-size: 10.5px;
  color: var(--text-faint);
}

.login-hint {
  flex-shrink: 0;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
  font-size: 12px;
  color: var(--text-faint);
  text-align: center;
}

@media (max-width: 900px) {
  .body :deep(.el-col) {
    width: 100%;
    max-width: 100%;
    flex: 0 0 100%;
  }

  .comments-card {
    position: static;
    max-height: none;
  }
}
</style>
