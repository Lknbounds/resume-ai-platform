<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  分析报告页。★ 照 getresumeiq.com/ats-resume-score 的布局做的。
 *
 *  【★ 参考到的东西（从它 CSS 扒的）】
 *
 *  摘要卡片：
 *      bg-gradient-to-br from-primary/5 via-background to-accent/10
 *      border border-primary/20 rounded-xl p-4 md:p-5 shadow-sm mb-4
 *      → 浅青渐变背景 + 品牌色淡边框 + 大圆角
 *
 *  卡片标题行：
 *      flex items-start gap-3 mb-4
 *      w-8 h-8 rounded-full bg-primary/10   ← 圆形图标底
 *
 *  四张卡片：优势 / 已匹配技能 / 需要补充 / 修改建议
 *
 *  【★ 为什么这个布局看起来"正式"？】
 *
 *  1. 大分数在顶部居中，一眼看到结论
 *  2. 摘要卡片用渐变背景，和其他卡片区分开
 *  3. **有"优势"卡片** —— 先肯定再提建议，不是一味挑错
 *  4. 四宫格，信息密度高但不拥挤
 *  5. 卡片间有呼吸感（gap + 圆角）
 * ═══════════════════════════════════════════════════════════════════════
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import BackButton from '@/components/BackButton.vue'
import PublishDialog from '@/components/PublishDialog.vue'
import { analysisApi } from '@/api/analysis'
import { resumeApi } from '@/api/resume'

const route = useRoute()
const router = useRouter()

const report = ref(null)
const loading = ref(true)

/** PDF 导出中 */
const exporting = ref(false)

/** 发布弹窗 */
const publishVisible = ref(false)
/** 报告对应的简历正文（发布弹窗预填用） */
const resumeContent = ref('')

onMounted(async () => {
  try {
    report.value = await analysisApi.detail(route.params.id)
  } finally {
    loading.value = false
  }
})

/**
 * 打开发布弹窗。
 *
 * ★ 为什么现在才去取简历正文？
 *   报告详情接口不返回正文（体积大）。
 *   只有用户真要发布时才需要它 —— 按需加载。
 */
async function openPublish() {
  if (!report.value?.resumeVersionNo) {
    return ElMessage.warning('报告缺少简历版本信息')
  }

  try {
    /*
     * ★ 这里需要简历版本的正文。
     *   报告里只有 resumeId 和 versionNo，没有 versionId。
     *   ★ 简化处理：用"简历详情 + 取第一个版本（最新）"。
     *     严谨做法是报告里存 resumeVersionId（其实表里有，只是 VO 没暴露）。
     */
    const detail = await resumeApi.detail(report.value.resumeId)
    const latestVersionId = detail.currentVersionId

    const v = await resumeApi.version(latestVersionId)
    resumeContent.value = v.content
    publishVisible.value = true
  } catch {
    ElMessage.error('获取简历内容失败')
  }
}

function onPublished(post) {
  router.push(`/community/${post.id}`)
}

/**
 * 导出 PDF。
 *
 * ★ 文件名用"简历标题-岗位-日期"，用户下载后一眼知道是哪份报告。
 */
async function exportPdf() {
  if (exporting.value) return
  exporting.value = true
  try {
    const name = `${report.value.resumeTitle || '简历'}-${report.value.jdTitle || '岗位'}.pdf`
    await analysisApi.exportPdf(report.value.id, name)
    ElMessage.success('报告已下载')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【等级 → 颜色】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 和后端 ScoreCalculator.grade() 的阈值必须一致：
 *      ≥85 EXCELLENT 绿 / ≥70 GOOD 蓝 / ≥55 FAIR 橙 / <55 WEAK 红
 *
 * ★ 用 CSS 变量而不是写死色值 —— 换主题时自动跟着变。
 */
const gradeColor = computed(() => {
  const g = report.value?.grade
  return {
    EXCELLENT: 'var(--success)',
    GOOD: 'var(--info)',
    FAIR: 'var(--warning)',
    WEAK: 'var(--danger)'
  }[g] || 'var(--text-faint)'
})

const gradeLabel = computed(() => ({
  EXCELLENT: '优秀',
  GOOD: '良好',
  FAIR: '一般',
  WEAK: '偏低'
}[report.value?.grade] || ''))

/** 分数对应的等级说明文字 */
const gradeHint = computed(() => ({
  EXCELLENT: '与目标岗位高度匹配',
  GOOD: '整体匹配良好',
  FAIR: '有一定基础，仍有提升空间',
  WEAK: '与目标岗位差距较大'
}[report.value?.grade] || ''))

/** 进度条颜色 */
function barColor(score) {
  if (score >= 85) return 'var(--success)'
  if (score >= 70) return 'var(--info)'
  if (score >= 55) return 'var(--warning)'
  return 'var(--danger)'
}

/** 重要度标签颜色 */
function importanceType(imp) {
  return { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }[imp] || 'info'
}

/** 严重程度 → 中文 */
const severityLabel = { HIGH: '优先改', MEDIUM: '建议改', LOW: '可选' }
function severityType(sev) {
  return { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }[sev] || 'info'
}

/** 板块名 → 中文 */
const sectionLabel = {
  SUMMARY: '自我评价', SKILLS: '技能', EXPERIENCE: '工作经历',
  PROJECT: '项目', EDUCATION: '教育', KEYWORDS: '关键词', OTHER: '其他'
}
</script>

<template>
  <DefaultLayout>
    <!--
      ★ 返回按钮：优先回到上一页（可能是刚提交分析的页面），
        没有历史时兜底到 /analysis/new。
    -->
    <BackButton to="/analysis/new" label="返回" />

    <el-skeleton v-if="loading" :rows="10" animated />

    <template v-else-if="report">

      <!-- ═══════════════ 1. 大分数区 ═══════════════ -->
      <section class="score-hero">
        <div class="score-ring">
          <!--
            ★ 分数用 64px 超大字号 + 语义色。
              这是整页的视觉锚点 —— 用户 3 秒内就知道"行不行"。
          -->
          <span class="score-num" :style="{ color: gradeColor }">
            {{ report.overallScore?.toFixed(0) }}
          </span>
          <span class="score-scale">/ 100</span>
        </div>

        <div class="score-grade" :style="{ color: gradeColor }">
          {{ gradeLabel }}
        </div>
        <p class="score-hint">{{ gradeHint }}</p>

        <div class="score-context">
          {{ report.resumeTitle }} · v{{ report.resumeVersionNo }}
          <span class="ctx-sep">对比</span>
          {{ report.jdTitle }}
        </div>
      </section>

      <!-- ═══════════════ 2. 摘要卡片（渐变背景）═══════════════ -->
      <section class="summary-card">
        <div class="summary-icon">✦</div>
        <div class="summary-body">
          <p class="summary-text">{{ report.summary }}</p>
          <div class="summary-meta">
            <span v-if="report.durationMs">耗时 {{ report.durationMs }}ms</span>
            <span class="dot">·</span>
            <span>{{ report.createdAt }}</span>
            <span class="dot">·</span>
            <span>算法 {{ report.scoringVersion }}</span>
            <el-tag v-if="report.degraded" size="small" type="warning" effect="plain">
              部分降级
            </el-tag>
          </div>
        </div>
      </section>

      <!-- ═══════════════ 3. 四宫格 ═══════════════ -->
      <div class="grid">
        <!-- ─────────── 优势 ─────────── -->
        <section class="card">
          <header class="card-head">
            <span class="card-icon ok">✓</span>
            <h2 class="card-title">优势</h2>
            <span v-if="report.strengths?.length" class="pill pill-ok">
              {{ report.strengths.length }}
            </span>
          </header>

          <ul v-if="report.strengths?.length" class="list">
            <li v-for="(s, i) in report.strengths" :key="i" class="list-item">
              <span class="bullet bullet-ok">•</span>
              <span>{{ s }}</span>
            </li>
          </ul>

          <p v-else class="empty">
            暂未发现明显优势，建议先补齐技能、经历、项目等基础信息
          </p>
        </section>

        <!-- ─────────── 已匹配技能 ─────────── -->
        <section class="card">
          <header class="card-head">
            <span class="card-icon ok">◎</span>
            <h2 class="card-title">已匹配技能</h2>
            <span v-if="report.hits?.length" class="pill pill-ok">
              {{ report.hits.length }}
            </span>
          </header>

          <div v-if="report.hits?.length" class="tags">
            <span
              v-for="k in report.hits"
              :key="k.name"
              class="tag tag-ok"
              :title="k.evidence || ''"
            >
              {{ k.name }}
            </span>
          </div>
          <p v-else class="empty">简历中没有命中 JD 要求的技能</p>

          <!-- 部分命中单独列，标注"写法可能不标准" -->
          <template v-if="report.partials?.length">
            <p class="sub-label">部分命中（建议改成标准写法）</p>
            <div class="tags">
              <span v-for="k in report.partials" :key="k.name" class="tag tag-warn">
                {{ k.name }}
              </span>
            </div>
          </template>
        </section>

        <!-- ─────────── 需要补充 ─────────── -->
        <section class="card">
          <header class="card-head">
            <span class="card-icon bad">!</span>
            <h2 class="card-title">需要补充的技能</h2>
            <span v-if="report.missing?.length" class="pill pill-bad">
              {{ report.missing.length }}
            </span>
          </header>

          <div v-if="report.missing?.length" class="tags">
            <span
              v-for="k in report.missing"
              :key="k.name"
              class="tag"
              :class="k.importance === 'HIGH' ? 'tag-bad' : 'tag-muted'"
            >
              {{ k.name }}
              <em v-if="k.importance === 'HIGH'" class="req">必须</em>
            </span>
          </div>
          <p v-else class="empty">JD 要求的技能已全部命中</p>

          <p v-if="report.missing?.some(k => k.importance === 'HIGH')" class="tip">
            标「必须」的是 JD 明确的硬性要求，建议优先补齐
          </p>
        </section>

        <!-- ─────────── 软技能提示 ─────────── -->
        <section v-if="report.softSkills?.length" class="card">
          <header class="card-head">
            <span class="card-icon">◇</span>
            <h2 class="card-title">软技能（不计分）</h2>
          </header>

          <div class="tags">
            <span v-for="s in report.softSkills" :key="s" class="tag tag-muted">
              {{ s }}
            </span>
          </div>

          <!--
            ★ 这段说明很重要：告诉用户"为什么软技能不参与评分"。
              否则用户会问"JD 要求团队协作，为什么不算分？"
          -->
          <p class="tip">
            软技能无法从简历文本可靠判断（"带过 5 人团队"能体现协作，
            但"善于沟通"只是自述），所以不参与评分。
            建议在项目描述里写明你的角色和协作范围。
          </p>
        </section>
      </div>

      <!-- ═══════════════ 4. 六维度 ═══════════════ -->
      <section class="card block">
        <header class="card-head">
          <span class="card-icon">▤</span>
          <h2 class="card-title">评分构成</h2>
        </header>
        <p class="card-sub">
          总分 = 各维度得分 × 权重 之和。「不适用」的维度（如 JD 没要求学历）不参与计算，权重会分给其他维度。
        </p>

        <div class="dims">
          <div v-for="d in report.dimensions" :key="d.key" class="dim">
            <div class="dim-top">
              <span class="dim-name">{{ d.name }}</span>
              <span class="dim-score" :style="{ color: d.applicable ? barColor(d.score) : 'var(--text-faint)' }">
                {{ d.applicable ? d.score?.toFixed(1) : '不适用' }}
              </span>
            </div>

            <div v-if="d.applicable" class="dim-bar">
              <div class="dim-fill"
                   :style="{ width: d.score + '%', background: barColor(d.score) }" />
            </div>

            <div class="dim-meta">
              <span v-if="d.applicable" class="dim-weight">{{ (d.weight * 100).toFixed(0) }}%</span>
              <span class="dim-detail">{{ d.detail }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- ═══════════════ 5. 改进建议 ═══════════════ -->
      <section v-if="report.suggestions?.length" class="card block">
        <header class="card-head">
          <span class="card-icon">✎</span>
          <h2 class="card-title">改进建议</h2>
          <span class="pill">{{ report.suggestions.length }}</span>
        </header>
        <p class="card-sub">按优先级排序，每条都给出了简历原文作为证据</p>

        <div class="suggestions">
          <div v-for="(s, i) in report.suggestions" :key="i" class="sug">
            <div class="sug-head">
              <el-tag size="small" :type="severityType(s.severity)" effect="plain">
                {{ severityLabel[s.severity] || s.severity }}
              </el-tag>
              <span class="sug-section">{{ sectionLabel[s.section] || s.section }}</span>
              <span class="sug-issue">{{ s.issue }}</span>
            </div>

            <div v-if="s.evidence" class="sug-evidence">
              <span class="ev-label">原文</span>
              <span class="ev-text">{{ s.evidence }}</span>
            </div>

            <p class="sug-action">{{ s.suggestion }}</p>
          </div>
        </div>
      </section>

      <!-- ═══════════════ 6. 底部操作 ═══════════════ -->
      <div class="footer-actions">
        <el-button
          type="primary"
          :loading="exporting"
          @click="exportPdf"
        >
          下载 PDF 报告
        </el-button>
        <el-button @click="openPublish">发布到社区</el-button>
        <el-button @click="router.push('/analysis/new')">再分析一次</el-button>
        <el-button @click="router.push(`/resumes/${report.resumeId}`)">回到简历</el-button>
      </div>

      <!-- ═══════════════ 发布弹窗 ═══════════════ -->
      <PublishDialog
        v-model:visible="publishVisible"
        :report="report"
        :resume-content="resumeContent"
        @published="onPublished"
      />
    </template>
  </DefaultLayout>
</template>

<style scoped>
/* ═══════════════════════════════════════════════════════════════
   1. 大分数区
   ═══════════════════════════════════════════════════════════════ */

.score-hero {
  text-align: center;
  padding: 16px 0 28px;
}

.score-ring {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 6px;
}

/*
 * ★ 64px + 极紧字距（-0.04em）—— 大数字的视觉冲击力。
 *   等宽数字保证不同分数对齐。
 */
.score-num {
  font-size: 64px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.04em;
  font-variant-numeric: tabular-nums;
}

.score-scale {
  font-size: 15px;
  color: var(--text-faint);
  font-weight: 500;
}

.score-grade {
  font-size: 15px;
  font-weight: 700;
  margin-top: 10px;
  letter-spacing: 0.06em;
}

.score-hint {
  font-size: 13px;
  color: var(--text-muted);
  margin: 6px 0 0;
}

.score-context {
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 14px;
}

.ctx-sep {
  margin: 0 8px;
  color: var(--line);
}

/* ═══════════════════════════════════════════════════════════════
   2. 摘要卡片 —— ★ 渐变背景（照 ResumeIQ 的 from-primary/5 to-accent/10）
   ═══════════════════════════════════════════════════════════════ */

.summary-card {
  display: flex;
  gap: 12px;

  /*
   * ★ 浅青 → 白 → 浅青的渐变，加上品牌色淡边框。
   *   这让摘要卡片和下面普通卡片在视觉上区分开，像"高亮区"。
   */
  background: linear-gradient(135deg,
      rgba(41, 163, 153, 0.06) 0%,
      #ffffff 50%,
      rgba(41, 163, 153, 0.08) 100%);
  border: 1px solid rgba(41, 163, 153, 0.2);
  border-radius: var(--radius-lg);

  padding: 16px 18px;
  margin-bottom: 16px;
}

.summary-icon {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(41, 163, 153, 0.14);
  color: var(--brand-deep);
  font-size: 13px;
  font-weight: 700;
  line-height: 28px;
  text-align: center;
}

.summary-body {
  flex: 1;
  min-width: 0;
}

.summary-text {
  font-size: 14px;
  line-height: 1.75;
  color: var(--text);
  margin: 0 0 8px;
}

.summary-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-faint);
  flex-wrap: wrap;
}

/* ═══════════════════════════════════════════════════════════════
   3. 四宫格
   ═══════════════════════════════════════════════════════════════ */

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}

.card {
  background: var(--surface);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  padding: 16px 18px;
}

/* 整宽卡片（评分构成、改进建议） */
.block {
  margin-bottom: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 12px;
}

/*
 * ★ 圆形图标底（照 ResumeIQ 的 w-8 h-8 rounded-full bg-primary/10）
 *   用不同色调区分卡片性质：绿=好、红=问题、灰=中性。
 */
.card-icon {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;

  background: var(--fill);
  color: var(--text-muted);
}

.card-icon.ok {
  background: rgba(22, 163, 74, 0.1);
  color: var(--success);
}

.card-icon.bad {
  background: rgba(220, 38, 38, 0.1);
  color: var(--danger);
}

.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  margin: 0;
}

.card-sub {
  font-size: 12px;
  color: var(--text-faint);
  margin: -4px 0 16px;
  line-height: 1.6;
}

/* 右上角数量徽标 */
.pill {
  margin-left: auto;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--fill);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.pill-ok {
  background: rgba(22, 163, 74, 0.1);
  color: var(--success);
}

.pill-bad {
  background: rgba(220, 38, 38, 0.1);
  color: var(--danger);
}

/* ═══════════════════════════════════════════════════════════════
   列表项（优势）
   ═══════════════════════════════════════════════════════════════ */

.list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.list-item {
  display: flex;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-muted);
  padding: 3px 0;
}

.bullet {
  flex-shrink: 0;
  color: var(--text-faint);
}

.bullet-ok {
  color: var(--success);
  font-weight: 700;
}

/* ═══════════════════════════════════════════════════════════════
   标签组（技能）
   ═══════════════════════════════════════════════════════════════ */

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;

  font-size: 11.5px;
  font-weight: 500;
  padding: 3px 9px;
  border-radius: var(--radius-sm);

  background: var(--fill);
  color: var(--text-muted);
}

.tag-ok {
  background: rgba(22, 163, 74, 0.09);
  color: #15803d;
}

.tag-warn {
  background: rgba(217, 119, 6, 0.09);
  color: #b45309;
}

.tag-bad {
  background: rgba(220, 38, 38, 0.08);
  color: #b91c1c;
}

.tag-muted {
  background: var(--fill);
  color: var(--text-muted);
}

/* 标签里的"必须"小标 */
.req {
  font-size: 9px;
  font-style: normal;
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(220, 38, 38, 0.15);
  color: #b91c1c;
}

.sub-label {
  font-size: 11px;
  color: var(--text-faint);
  margin: 14px 0 7px;
}

.empty {
  font-size: 12px;
  color: var(--text-faint);
  margin: 0;
  line-height: 1.7;
}

.tip {
  font-size: 11px;
  color: var(--text-faint);
  line-height: 1.7;
  margin: 12px 0 0;
  padding-top: 10px;
  border-top: 1px solid var(--line-soft);
}

/* ═══════════════════════════════════════════════════════════════
   六维度
   ═══════════════════════════════════════════════════════════════ */

.dims {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dim-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 6px;
}

.dim-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
}

.dim-score {
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.dim-bar {
  height: 5px;
  background: var(--fill);
  border-radius: 999px;
  overflow: hidden;
}

.dim-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.5s ease-out;
}

.dim-meta {
  display: flex;
  gap: 10px;
  margin-top: 5px;
  font-size: 11px;
  color: var(--text-faint);
}

.dim-weight {
  flex-shrink: 0;
  color: var(--brand-deep);
  font-weight: 500;
}

/* ═══════════════════════════════════════════════════════════════
   建议
   ═══════════════════════════════════════════════════════════════ */

.suggestions {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sug {
  padding: 13px 15px;
  background: #fafbfc;
  border-radius: 10px;
  border-left: 3px solid var(--line);
}

.sug-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.sug-section {
  font-size: 11px;
  color: var(--text-faint);
  padding-top: 2px;
}

.sug-issue {
  flex: 1;
  min-width: 200px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text);
  line-height: 1.6;
}

.sug-evidence {
  display: flex;
  gap: 8px;
  padding: 7px 10px;
  background: var(--surface);
  border-radius: 6px;
  margin-bottom: 8px;
  font-size: 11.5px;
  border: 1px solid var(--line-soft);
}

.ev-label {
  flex-shrink: 0;
  color: var(--text-faint);
  font-weight: 500;
}

.ev-text {
  color: var(--text-muted);
  line-height: 1.6;
}

.sug-action {
  font-size: 12px;
  line-height: 1.75;
  color: var(--text-muted);
  margin: 0;
}

/* ═══════════════════════════════════════════════════════════════
   底部
   ═══════════════════════════════════════════════════════════════ */

.footer-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
  padding: 20px 0 8px;
  flex-wrap: wrap;
}

/* ═══════════════════════════════════════════════════════════════
   窄屏
   ═══════════════════════════════════════════════════════════════ */

@media (max-width: 760px) {
  .grid {
    grid-template-columns: 1fr;
  }

  .score-num {
    font-size: 48px;
  }
}
</style>
