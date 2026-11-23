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

/** 采纳建议 */
const adoptVisible = ref(false)
const adoptComment = ref(null)
const adoptMode = ref('version')
const adoptContent = ref('')
const adoptChangeNote = ref('')
const adoptSaving = ref(false)

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

/** 打开采纳弹窗。默认选择“生成新版本”，并预填当前帖子里的简历正文。 */
function openAdopt(comment) {
  adoptComment.value = comment
  adoptMode.value = 'version'
  adoptContent.value = post.value?.resumeContent || ''
  adoptChangeNote.value = ''
  adoptVisible.value = true
}

function beforeAdoptClose(done) {
  if (!adoptSaving.value) done()
}

async function confirmAdopt() {
  if (!adoptComment.value || adoptSaving.value) return

  const payload = {}
  const currentContent = (post.value?.resumeContent || '').trim()

  if (adoptMode.value === 'version') {
    const nextContent = adoptContent.value.trim()
    if (!nextContent) {
      ElMessage.warning('请填写修改后的简历正文')
      return
    }
    if (nextContent.length < 50) {
      ElMessage.warning('简历正文至少需要 50 个字')
      return
    }
    if (nextContent === currentContent) {
      ElMessage.warning('正文还没有修改。可先改一句建议对应的内容，再生成新版本')
      return
    }
    payload.updatedResumeContent = nextContent
    if (adoptChangeNote.value.trim()) {
      payload.changeNote = adoptChangeNote.value.trim()
    }
  }

  adoptSaving.value = true
  try {
    const result = await commentApi.adopt(adoptComment.value.id, payload)
    adoptVisible.value = false
    await loadComments()

    if (result.newVersionId) {
      let shouldView = false
      try {
        await ElMessageBox.confirm(
          `已生成 V${result.versionNo} 并开始重新分析。前往简历详情即可查看新版本和最新报告。`,
          '采纳成功',
          {
            confirmButtonText: '查看新版本',
            cancelButtonText: '留在此页',
            type: 'success'
          }
        )
        shouldView = true
      } catch {
        shouldView = false
      }

      if (shouldView) router.push(`/resumes/${result.resumeId}`)
    } else if (adoptMode.value === 'version') {
      ElMessage.warning('已标记采纳；来源报告已删除，本次未生成新版本')
    } else {
      ElMessage.success('已标记采纳')
    }
  } finally {
    adoptSaving.value = false
  }
}
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
      <div class="studio-paper post-head">
        <div class="head-main">
          <p class="studio-eyebrow">COMMUNITY REVIEW</p>
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
          :aria-label="`有帮助，当前 ${post.upCount} 票`"
          @click="votePost('UP')"
        >
          <el-icon><CaretTop /></el-icon>
          有帮助 {{ post.upCount }}
        </el-button>

        <el-button
          size="small"
          :disabled="!canInteract"
          :aria-label="`没有帮助，当前 ${post.downCount} 票`"
          @click="votePost('DOWN')"
        >
          <el-icon><CaretBottom /></el-icon>
          {{ post.downCount }}
        </el-button>

        <span class="stat">
          <el-icon><ChatDotRound /></el-icon>
          {{ post.commentCount }}
        </span>
        <span class="stat">
          <el-icon><View /></el-icon>
          {{ post.viewCount }}
        </span>

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
          <section class="studio-paper card resume-card">
            <header class="card-head">
              <div>
                <p class="panel-kicker">RESUME SNAPSHOT</p>
                <span class="card-title">简历内容</span>
              </div>
              <span class="card-hint">已通过个人信息审核</span>
            </header>
            <div class="pre-wrap resume-text">{{ post.resumeContent }}</div>
          </section>

          <!-- 报告快照 -->
          <section v-if="post.report" class="studio-paper card">
            <header class="card-head">
              <div>
                <p class="panel-kicker">REVIEW REPORT</p>
                <span class="card-title">分析报告</span>
              </div>
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
          <section class="studio-paper card comments-card">
            <header class="card-head">
              <span class="card-title">讨论 {{ post.commentCount }}</span>
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
                    type="button"
                    class="mini-btn"
                    :class="{ active: c.myLiked }"
                    :disabled="!canInteract"
                    @click="voteComment(c, 'UP')"
                  >
                    <el-icon><CaretTop /></el-icon>
                    {{ c.likeCount }}
                  </button>
                  <button
                    type="button"
                    class="mini-btn"
                    :class="{ active: c.myUseful }"
                    :disabled="!canInteract"
                    @click="voteComment(c, 'USEFUL')"
                  >
                    有用 {{ c.usefulCount }}
                  </button>
                  <button v-if="canInteract" type="button" class="mini-btn" @click="startReply(c)">回复</button>
                  <button
                    v-if="c.canAdopt"
                    type="button"
                    class="mini-btn adopt-btn"
                    @click="openAdopt(c)"
                  >
                    采纳
                  </button>
                  <button
                    v-if="c.canDelete"
                    type="button"
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
                      <el-tag v-if="r.adopted" size="small" type="success" effect="dark">
                        已采纳
                      </el-tag>
                      <span class="c-time">{{ relativeTime(r.createdAt) }}</span>
                    </div>
                    <p class="c-content">{{ r.content }}</p>
                    <div class="c-actions">
                      <button
                        type="button"
                        class="mini-btn"
                        :class="{ active: r.myLiked }"
                        :disabled="!canInteract"
                        @click="voteComment(r, 'UP')"
                      >
                        <el-icon><CaretTop /></el-icon>
                        {{ r.likeCount }}
                      </button>
                      <button
                        type="button"
                        class="mini-btn"
                        :class="{ active: r.myUseful }"
                        :disabled="!canInteract"
                        @click="voteComment(r, 'USEFUL')"
                      >
                        有用 {{ r.usefulCount }}
                      </button>
                      <button
                        v-if="r.canAdopt"
                        type="button"
                        class="mini-btn adopt-btn"
                        @click="openAdopt(r)"
                      >
                        采纳
                      </button>
                      <button
                        v-if="r.canDelete"
                        type="button"
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

    <!-- 采纳建议：标记认可，或基于建议直接生成简历新版本 -->
    <el-dialog
      v-model="adoptVisible"
      class="adopt-dialog"
      width="680px"
      align-center
      destroy-on-close
      :show-close="!adoptSaving"
      :close-on-click-modal="!adoptSaving"
      :close-on-press-escape="!adoptSaving"
      :before-close="beforeAdoptClose"
    >
      <template #header>
        <div class="adopt-heading">
          <p class="studio-eyebrow">TURN FEEDBACK INTO A VERSION</p>
          <h2>采纳这条建议</h2>
        </div>
      </template>

      <div v-if="adoptComment" class="adopt-body">
        <blockquote class="adopt-quote">
          <div class="adopt-quote-meta">
            <span>{{ adoptComment.author.nickname }}</span>
            <span class="quote-dot">·</span>
            <span>{{ adoptComment.sectionLabel }}</span>
          </div>
          <p>{{ adoptComment.content }}</p>
        </blockquote>

        <div class="adopt-mode-title">处理方式</div>
        <el-radio-group v-model="adoptMode" class="adopt-mode-grid">
          <el-radio value="version" border class="adopt-mode-card">
            <span class="mode-name">采纳并生成新版本</span>
            <span class="mode-desc">在来源简历上新增版本，并自动重新分析</span>
          </el-radio>
          <el-radio value="mark" border class="adopt-mode-card">
            <span class="mode-name">仅标记采纳</span>
            <span class="mode-desc">认可这条建议，不修改简历内容</span>
          </el-radio>
        </el-radio-group>

        <div v-if="adoptMode === 'version'" class="adopt-editor">
          <div class="editor-label">
            <label for="adopt-content">修改后的简历正文</label>
            <span>{{ adoptContent.trim().length }} 字</span>
          </div>
          <el-input
            id="adopt-content"
            v-model="adoptContent"
            type="textarea"
            :rows="12"
            maxlength="200000"
            resize="vertical"
            placeholder="在帖子简历的基础上，按这条建议修改"
          />

          <div class="editor-label change-label">
            <label for="adopt-change-note">变更说明</label>
            <span>选填</span>
          </div>
          <el-input
            id="adopt-change-note"
            v-model="adoptChangeNote"
            maxlength="255"
            show-word-limit
            placeholder="例如：补充项目 QPS 与缓存命中率"
          />

          <p class="adopt-tip">
            正文已预填帖子里的版本。请至少修改一处相关表述；内容未变化时不会创建新版本。
          </p>
        </div>
      </div>

      <template #footer>
        <div class="adopt-footer">
          <span>新版本只会写入你自己的简历</span>
          <div>
            <el-button :disabled="adoptSaving" @click="adoptVisible = false">取消</el-button>
            <el-button type="primary" :loading="adoptSaving" @click="confirmAdopt">
              确认采纳
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>
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

/* ═══════════════ 方案 C：职业工作室覆盖层 ═══════════════ */
.post-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 30px;
  margin-bottom: 16px;
  padding: 28px 30px;
  border-radius: 22px;
  background:
    radial-gradient(circle at 90% 10%, rgba(242, 193, 91, 0.18), transparent 12rem),
    var(--surface);
}

.post-title {
  max-width: 760px;
  font-family: var(--font-serif);
  font-size: clamp(30px, 4vw, 45px);
  line-height: 1.22;
  letter-spacing: -0.035em;
  color: var(--text);
  margin: 0;
}

.post-meta { margin-top: 17px; }
.post-content {
  max-width: 760px;
  margin-top: 18px;
  padding-top: 17px;
  border-top: 1px solid var(--line-soft);
  font-size: 13px;
  line-height: 1.8;
}

.head-side {
  min-width: 104px;
  padding: 15px 16px;
  border-radius: 16px;
  background: var(--brand);
  color: #fff;
}

.big-score {
  font-family: var(--font-serif);
  font-size: 46px;
  color: #fff !important;
}

.score-label { color: rgba(255, 255, 255, 0.62); }

.vote-bar {
  padding: 10px 12px;
  border: 1px solid var(--line-soft);
  border-radius: 14px;
  background: rgba(255, 252, 247, 0.76);
}

.stat { display: inline-flex; align-items: center; gap: 5px; margin-inline: 3px; }

.card {
  border-radius: 18px;
  box-shadow: var(--shadow-sm);
}

.panel-kicker {
  margin: 0 0 3px;
  color: var(--accent-deep);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.card-title { font-family: var(--font-serif); font-size: 18px; font-weight: 700; }
.resume-text { color: var(--text); font-family: var(--font-serif); font-size: 13.5px; line-height: 2; }
.dim-bar { height: 5px; }
.comments-card { top: 92px; }
.reply-hint { color: var(--accent-deep); }
.mini-btn { min-height: 32px; }

@media (max-width: 900px) {
  .post-head { padding: 22px; }
  .head-side { min-width: 88px; }
  .vote-bar { flex-wrap: wrap; }
}

@media (max-width: 620px) {
  .post-head { align-items: flex-start; flex-direction: column; padding: 20px 17px; }
  .post-title { font-size: 30px; }
  .head-side { display: flex; align-items: center; gap: 10px; padding: 10px 14px; }
  .big-score { font-size: 32px; }
  .score-label { margin: 0; }
  .vote-bar :deep(.el-button) { flex: 1 1 120px; }
  .card { padding: 15px; }
  .comment-filters { flex-wrap: wrap; justify-content: flex-end; }
  .mini-btn { min-height: 44px; }
  .composer-foot { align-items: flex-start; flex-direction: column; }
  .composer-foot :deep(.el-button) { width: 100%; }
}
/* ═══════════════ 采纳建议 ═══════════════ */
.adopt-btn {
  color: var(--accent-deep);
  background: rgba(242, 193, 91, 0.17);
  font-weight: 700;
}

.adopt-btn:hover:not(:disabled) {
  color: #fff;
  background: var(--accent-deep);
}

:global(.adopt-dialog) {
  overflow: hidden;
  border-radius: 24px;
  background: var(--surface);
  box-shadow: 0 28px 90px rgba(31, 45, 39, 0.2);
}

:global(.adopt-dialog .el-dialog__header) {
  margin: 0;
  padding: 28px 30px 18px;
  border-bottom: 1px solid var(--line-soft);
}

:global(.adopt-dialog .el-dialog__body) {
  padding: 22px 30px 10px;
}

:global(.adopt-dialog .el-dialog__footer) {
  padding: 16px 30px 24px;
}

.adopt-heading h2 {
  margin: 3px 0 0;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 28px;
  letter-spacing: -0.025em;
}

.adopt-quote {
  margin: 0 0 22px;
  padding: 16px 18px;
  border: 1px solid rgba(31, 75, 62, 0.13);
  border-left: 4px solid var(--brand);
  border-radius: 0 14px 14px 0;
  background: rgba(31, 75, 62, 0.045);
}

.adopt-quote-meta {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 7px;
  color: var(--brand-deep);
  font-size: 11px;
  font-weight: 700;
}

.quote-dot { color: var(--text-faint); }

.adopt-quote p {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-wrap;
}

.adopt-mode-title {
  margin-bottom: 9px;
  color: var(--text);
  font-size: 11px;
  font-weight: 700;
}

.adopt-mode-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  width: 100%;
  margin-bottom: 20px;
}

.adopt-mode-card {
  align-items: flex-start;
  width: 100%;
  height: auto;
  min-height: 76px;
  margin: 0 !important;
  padding: 13px 14px !important;
  border-color: var(--line-soft) !important;
  border-radius: 14px !important;
  background: rgba(255, 252, 247, 0.7);
}

.adopt-mode-card.is-checked {
  border-color: var(--brand) !important;
  background: var(--brand-soft) !important;
}

.adopt-mode-card :deep(.el-radio__label) {
  display: grid;
  gap: 4px;
  padding-left: 8px;
  white-space: normal;
}

.mode-name {
  color: var(--text);
  font-size: 12.5px;
  font-weight: 700;
}

.mode-desc {
  color: var(--text-faint);
  font-size: 10.5px;
  line-height: 1.5;
}

.adopt-editor {
  padding: 16px;
  border: 1px solid var(--line-soft);
  border-radius: 16px;
  background: rgba(255, 252, 247, 0.72);
}

.editor-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--text);
  font-size: 11px;
  font-weight: 700;
}

.editor-label span {
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 500;
}

.change-label { margin-top: 14px; }

.adopt-editor :deep(.el-textarea__inner),
.adopt-editor :deep(.el-input__wrapper) {
  border-radius: 10px;
  background: #fffdf9;
  box-shadow: 0 0 0 1px var(--line-soft) inset;
}

.adopt-editor :deep(.el-textarea__inner) {
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 12.5px;
  line-height: 1.8;
}

.adopt-tip {
  margin: 11px 0 0;
  color: var(--text-faint);
  font-size: 10.5px;
  line-height: 1.6;
}

.adopt-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.adopt-footer > span {
  color: var(--text-faint);
  font-size: 10.5px;
}

</style>
