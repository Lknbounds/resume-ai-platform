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

const gradeOnDarkColor = computed(() => ({
  EXCELLENT: '#b8dfc8',
  GOOD: '#b9d9e8',
  FAIR: '#f2c15b',
  WEAK: '#ffb0a2'
}[report.value?.grade] || '#f8f3e9'))

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

/** 顶部只展示真实建议里的前三条，不额外编造任务。 */
const prioritySuggestions = computed(() => (report.value?.suggestions || []).slice(0, 3))
</script>

<template>
  <DefaultLayout>
    <BackButton to="/analysis/new" label="返回分析" />

    <div v-if="loading" class="loading-panel">
      <el-skeleton :rows="10" animated />
    </div>

    <template v-else-if="report">
      <header class="report-head">
        <div class="report-head-copy">
          <p class="eyebrow"><span /> MATCH REVIEW · REPORT</p>
          <h1>把优势，<em>讲到点子上。</em></h1>
          <p class="report-sub">
            这份报告把岗位要求、简历证据和下一步动作放在一起，方便你直接开始修改。
          </p>
        </div>

        <aside class="report-target">
          <span class="target-label">本次对照岗位</span>
          <strong>{{ report.jdTitle }}</strong>
          <p>{{ report.resumeTitle }} · v{{ report.resumeVersionNo }}</p>
          <div class="target-meta">
            <span>{{ report.createdAt }}</span>
            <span v-if="report.durationMs">耗时 {{ report.durationMs }}ms</span>
          </div>
          <el-tag v-if="report.degraded" size="small" type="warning" effect="plain">
            部分降级
          </el-tag>
        </aside>
      </header>

      <section class="overview" aria-label="报告概览">
        <article class="score-card">
          <div class="score-card-head">
            <span>OVERALL MATCH</span>
            <span class="grade-chip">{{ gradeLabel }}</span>
          </div>

          <div class="score-value" :style="{ color: gradeOnDarkColor }">
            <strong>{{ report.overallScore?.toFixed(0) }}</strong>
            <span>/ 100</span>
          </div>
          <p class="score-hint">{{ gradeHint }}</p>

          <div class="score-foot">
            <span class="score-line" aria-hidden="true" />
            <small>算法版本 {{ report.scoringVersion }}</small>
          </div>
        </article>

        <article class="map-card">
          <header class="section-title-row">
            <div>
              <span class="section-label">MATCH MAP</span>
              <h2>岗位匹配地图</h2>
            </div>
            <span class="count-note">六维评分</span>
          </header>

          <div class="map-list">
            <div v-for="d in report.dimensions" :key="d.key" class="map-row">
              <div class="map-row-top">
                <span>{{ d.name }}</span>
                <strong :style="{ color: d.applicable ? barColor(d.score) : 'var(--text-faint)' }">
                  {{ d.applicable ? d.score?.toFixed(1) : '—' }}
                </strong>
              </div>
              <div
                v-if="d.applicable"
                class="map-track"
                role="progressbar"
                :aria-label="d.name"
                :aria-valuenow="d.score"
                aria-valuemin="0"
                aria-valuemax="100"
              >
                <span :style="{ width: d.score + '%', background: barColor(d.score) }" />
              </div>
              <div v-else class="not-applicable">本次岗位未要求，不参与计分</div>
            </div>
          </div>
        </article>

        <article class="priority-card">
          <header class="section-title-row">
            <div>
              <span class="section-label">NEXT MOVES</span>
              <h2>本周先做三件事</h2>
            </div>
            <span v-if="prioritySuggestions.length" class="count-badge">{{ report.suggestions.length }} 条建议</span>
          </header>

          <ol v-if="prioritySuggestions.length" class="priority-list">
            <li v-for="(item, index) in prioritySuggestions" :key="index">
              <span class="priority-no">{{ String(index + 1).padStart(2, '0') }}</span>
              <div>
                <span class="priority-meta">
                  {{ sectionLabel[item.section] || item.section }} ·
                  {{ severityLabel[item.severity] || item.severity }}
                </span>
                <p>{{ item.issue }}</p>
              </div>
            </li>
          </ol>
          <p v-else class="empty-state">这份报告没有需要优先修正的问题。</p>

          <p v-if="report.suggestions?.length > 3" class="priority-more">
            下方还有 {{ report.suggestions.length - 3 }} 条补充建议。
          </p>
        </article>
      </section>

      <section class="summary-card">
        <span class="summary-mark" aria-hidden="true">简</span>
        <div>
          <span class="section-label">REVIEW NOTE</span>
          <h2>评审摘要</h2>
          <p>{{ report.summary }}</p>
          <div class="summary-meta">
            <span>{{ report.resumeTitle }} · v{{ report.resumeVersionNo }}</span>
            <span class="meta-dot">·</span>
            <span>{{ report.jdTitle }}</span>
          </div>
        </div>
      </section>

      <div class="evidence-grid">
        <section class="paper-card strength-card">
          <header class="paper-head">
            <div>
              <span class="section-label">WHAT ALREADY WORKS</span>
              <h2>简历里已有的好证据</h2>
            </div>
            <span v-if="report.strengths?.length" class="paper-count">{{ report.strengths.length }}</span>
          </header>

          <ul v-if="report.strengths?.length" class="strength-list">
            <li v-for="(item, index) in report.strengths" :key="index">
              <span>{{ String(index + 1).padStart(2, '0') }}</span>
              <p>{{ item }}</p>
            </li>
          </ul>
          <p v-else class="empty-state">
            暂未发现明显优势。建议先补齐技能、经历、项目等基础信息。
          </p>

          <div class="skill-block">
            <div class="skill-heading">
              <h3>已匹配技能</h3>
              <span v-if="report.hits?.length">{{ report.hits.length }} 项</span>
            </div>
            <div v-if="report.hits?.length" class="tags">
              <span v-for="k in report.hits" :key="k.name" class="tag tag-ok" :title="k.evidence || ''">
                {{ k.name }}
              </span>
            </div>
            <p v-else class="muted-copy">简历中没有命中 JD 要求的技能。</p>

            <template v-if="report.partials?.length">
              <p class="sub-label">部分命中，建议改成岗位里的标准写法</p>
              <div class="tags">
                <span v-for="k in report.partials" :key="k.name" class="tag tag-warn">{{ k.name }}</span>
              </div>
            </template>
          </div>
        </section>

        <section class="paper-card gap-card">
          <header class="paper-head">
            <div>
              <span class="section-label">WHAT TO ADD NEXT</span>
              <h2>还需要补上的证据</h2>
            </div>
            <span v-if="report.missing?.length" class="paper-count accent">{{ report.missing.length }}</span>
          </header>

          <div v-if="report.missing?.length" class="tags tags-large">
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
          <p v-else class="empty-state">JD 要求的技能已经全部命中。</p>

          <p v-if="report.missing?.some(k => k.importance === 'HIGH')" class="inline-tip">
            标「必须」的是 JD 明确的硬性要求，建议优先补齐。
          </p>

          <div v-if="report.softSkills?.length" class="soft-block">
            <div class="skill-heading">
              <h3>软技能观察</h3>
              <span>不计分</span>
            </div>
            <div class="tags">
              <span v-for="skill in report.softSkills" :key="skill" class="tag tag-muted">{{ skill }}</span>
            </div>
            <p class="soft-note">
              软技能很难只靠简历文字可靠判断。建议在项目描述里写清你的角色、协作范围和实际影响。
            </p>
          </div>
        </section>
      </div>

      <section class="dimension-section">
        <header class="section-title-row dimension-title">
          <div>
            <span class="section-label">SCORING DETAIL</span>
            <h2>评分构成</h2>
          </div>
          <p>总分由各维度得分与权重加权得出；标注不适用的维度不参与计算。</p>
        </header>

        <div class="dimension-grid">
          <article v-for="d in report.dimensions" :key="d.key" class="dimension-item">
            <div class="dimension-top">
              <h3>{{ d.name }}</h3>
              <strong :style="{ color: d.applicable ? barColor(d.score) : 'var(--text-faint)' }">
                {{ d.applicable ? d.score?.toFixed(1) : '不适用' }}
              </strong>
            </div>
            <div v-if="d.applicable" class="dimension-track" aria-hidden="true">
              <span :style="{ width: d.score + '%', background: barColor(d.score) }" />
            </div>
            <div class="dimension-meta">
              <span v-if="d.applicable">权重 {{ (d.weight * 100).toFixed(0) }}%</span>
              <span v-else>本次不计分</span>
            </div>
            <p>{{ d.detail }}</p>
          </article>
        </div>
      </section>

      <section v-if="report.suggestions?.length" class="suggestion-section">
        <header class="section-title-row suggestion-title">
          <div>
            <span class="section-label">EDITOR'S NOTES</span>
            <h2>逐条改进建议</h2>
          </div>
          <span class="count-note">按优先级排序</span>
        </header>

        <div class="suggestion-list">
          <article v-for="(item, index) in report.suggestions" :key="index" class="suggestion-item">
            <div class="suggestion-index">{{ String(index + 1).padStart(2, '0') }}</div>
            <div class="suggestion-body">
              <div class="suggestion-head">
                <el-tag size="small" :type="severityType(item.severity)" effect="plain">
                  {{ severityLabel[item.severity] || item.severity }}
                </el-tag>
                <span class="suggestion-section-name">{{ sectionLabel[item.section] || item.section }}</span>
                <strong>{{ item.issue }}</strong>
              </div>

              <div v-if="item.evidence" class="evidence-row">
                <span>简历原文</span>
                <p>{{ item.evidence }}</p>
              </div>

              <div class="action-row">
                <span>怎么改</span>
                <p>{{ item.suggestion }}</p>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="action-bar" aria-label="报告操作">
        <div class="action-copy">
          <span class="section-label">KEEP GOING</span>
          <strong>修改完成后，再回来看看新的匹配结果。</strong>
        </div>
        <div class="action-buttons">
          <el-button type="primary" :loading="exporting" @click="exportPdf">
            下载 PDF 报告
          </el-button>
          <el-button @click="openPublish">发布到社区</el-button>
          <el-button @click="router.push('/analysis/new')">再分析一次</el-button>
          <el-button @click="router.push('/resumes/' + report.resumeId)">回到简历</el-button>
        </div>
      </section>

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
.loading-panel { padding: 28px; border: 1px solid var(--line-soft); border-radius: var(--radius-lg); background: var(--surface); }
.report-head { display: grid; grid-template-columns: minmax(0, 1fr) 330px; align-items: end; gap: clamp(34px, 7vw, 92px); padding: 18px 0 40px; }
.eyebrow, .section-label { color: var(--brand); font-size: 9px; font-weight: 700; letter-spacing: 0.18em; }
.eyebrow { display: flex; align-items: center; gap: 9px; margin: 0 0 17px; }
.eyebrow span { width: 22px; height: 1px; background: var(--accent); }
.report-head h1 { margin: 0; color: var(--text); font-family: var(--font-serif); font-size: clamp(40px, 5vw, 62px); line-height: 1.1; letter-spacing: -0.055em; }
.report-head h1 em { color: var(--brand); font-style: normal; }
.report-sub { max-width: 640px; margin: 18px 0 0; color: var(--text-muted); font-size: 13.5px; line-height: 1.8; }
.report-target { padding: 22px 24px; border-left: 3px solid var(--accent); border-radius: 0 16px 16px 0; background: var(--surface); box-shadow: var(--shadow-sm); }
.target-label { color: var(--text-faint); font-size: 9px; font-weight: 700; letter-spacing: 0.13em; }
.report-target > strong { display: block; margin: 9px 0 5px; color: var(--text); font-family: var(--font-serif); font-size: 21px; line-height: 1.4; }
.report-target > p { margin: 0; color: var(--text-muted); font-size: 11px; }
.target-meta { display: flex; flex-wrap: wrap; gap: 6px 13px; margin-top: 14px; color: var(--text-faint); font-size: 9.5px; }
.report-target :deep(.el-tag) { margin-top: 12px; }
.overview { display: grid; grid-template-columns: 0.83fr 1.22fr 1fr; gap: 16px; margin-bottom: 16px; }
.score-card, .map-card, .priority-card, .paper-card, .dimension-section, .suggestion-section, .summary-card { border-radius: var(--radius-lg); }
.score-card { min-height: 330px; padding: 25px; display: flex; flex-direction: column; background: radial-gradient(circle at 100% 0%, rgba(242, 193, 91, 0.18), transparent 12rem), var(--brand); color: #fdf8ed; box-shadow: 0 18px 36px rgba(31, 75, 62, 0.16); }
.score-card-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: rgba(253, 248, 237, 0.55); font-family: var(--font-mono); font-size: 8px; letter-spacing: 0.14em; }
.grade-chip { padding: 5px 9px; border-radius: 999px; background: rgba(255, 255, 255, 0.11); color: #fdf8ed; font-family: var(--font-sans); font-size: 10px; letter-spacing: 0; }
.score-value { display: flex; align-items: baseline; gap: 7px; margin-top: 42px; }
.score-value strong { font-family: var(--font-serif); font-size: 84px; line-height: 0.86; letter-spacing: -0.07em; font-variant-numeric: tabular-nums; }
.score-value span { color: rgba(253, 248, 237, 0.48); font-size: 12px; }
.score-hint { margin: 20px 0 0; color: rgba(253, 248, 237, 0.8); font-size: 13px; }
.score-foot { margin-top: auto; padding-top: 22px; }
.score-line { display: block; width: 100%; height: 1px; margin-bottom: 12px; background: rgba(255, 255, 255, 0.16); }
.score-foot small { color: rgba(253, 248, 237, 0.42); font-size: 9px; }
.map-card, .priority-card { min-height: 330px; padding: 24px; border: 1px solid var(--line-soft); background: var(--surface); }
.section-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.section-title-row h2 { margin: 5px 0 0; color: var(--text); font-family: var(--font-serif); font-size: 23px; letter-spacing: -0.03em; }
.section-label { display: block; color: var(--accent-deep); }
.count-note, .count-badge { color: var(--text-faint); font-size: 9.5px; white-space: nowrap; }
.count-badge { padding: 5px 9px; border-radius: 999px; background: var(--accent-soft); color: var(--accent-deep); font-weight: 700; }
.map-list { display: flex; flex-direction: column; gap: 13px; margin-top: 22px; }
.map-row-top { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 5px; color: var(--text-muted); font-size: 11px; }
.map-row-top strong { font-size: 11px; font-weight: 700; font-variant-numeric: tabular-nums; }
.map-track, .dimension-track { height: 5px; overflow: hidden; border-radius: 999px; background: var(--fill); }
.map-track span, .dimension-track span { display: block; height: 100%; border-radius: inherit; }
.not-applicable { color: var(--text-faint); font-size: 9px; }
.priority-list { display: flex; flex-direction: column; gap: 0; padding: 0; margin: 18px 0 0; list-style: none; }
.priority-list li { display: grid; grid-template-columns: 26px 1fr; gap: 10px; padding: 13px 0; border-bottom: 1px solid var(--line-soft); }
.priority-list li:last-child { border-bottom: 0; }
.priority-no { color: var(--accent); font-family: var(--font-mono); font-size: 9px; font-weight: 700; }
.priority-meta { color: var(--text-faint); font-size: 9px; }
.priority-list p { margin: 4px 0 0; color: var(--text); font-size: 11.5px; font-weight: 600; line-height: 1.55; }
.priority-more { margin: 12px 0 0; color: var(--text-faint); font-size: 9.5px; }
.summary-card { display: flex; gap: 18px; padding: 24px 26px; border: 1px solid rgba(31, 75, 62, 0.11); background: linear-gradient(100deg, var(--brand-soft), var(--surface) 78%); margin-bottom: 16px; }
.summary-mark { display: flex; width: 42px; height: 42px; flex-shrink: 0; align-items: center; justify-content: center; border-radius: 50%; background: var(--brand); color: #fff; font-family: var(--font-serif); font-size: 18px; }
.summary-card h2 { margin: 5px 0 8px; font-family: var(--font-serif); font-size: 21px; }
.summary-card p { margin: 0; color: var(--text-muted); font-size: 12.5px; line-height: 1.8; }
.summary-meta { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; color: var(--text-faint); font-size: 9.5px; }
.meta-dot { color: var(--line); }
.evidence-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
.paper-card { padding: 26px; border: 1px solid var(--line); background: linear-gradient(rgba(255, 252, 247, 0.96), rgba(255, 252, 247, 0.96)), repeating-linear-gradient(0deg, transparent 0 31px, rgba(112, 95, 70, 0.07) 31px 32px); }
.paper-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 16px; border-bottom: 1px solid var(--line-soft); }
.paper-head h2 { margin: 5px 0 0; font-family: var(--font-serif); font-size: 23px; letter-spacing: -0.03em; }
.paper-count { display: flex; width: 31px; height: 31px; flex-shrink: 0; align-items: center; justify-content: center; border-radius: 50%; background: var(--brand-soft); color: var(--brand); font-size: 10px; font-weight: 700; }
.paper-count.accent { background: var(--accent-soft); color: var(--accent-deep); }
.strength-list { padding: 0; margin: 10px 0 22px; list-style: none; }
.strength-list li { display: grid; grid-template-columns: 30px 1fr; gap: 9px; padding: 10px 0; border-bottom: 1px solid rgba(235, 227, 215, 0.75); }
.strength-list span { color: var(--accent-deep); font-family: var(--font-mono); font-size: 8.5px; font-weight: 700; }
.strength-list p { margin: 0; color: var(--text-muted); font-size: 11.5px; line-height: 1.7; }
.skill-block, .soft-block { margin-top: 20px; padding-top: 18px; border-top: 1px solid var(--line-soft); }
.skill-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 11px; }
.skill-heading h3 { margin: 0; color: var(--text); font-size: 12px; }
.skill-heading span { color: var(--text-faint); font-size: 9px; }
.tags { display: flex; flex-wrap: wrap; gap: 7px; }
.tags-large { margin-top: 20px; }
.tag { display: inline-flex; align-items: center; gap: 5px; padding: 5px 10px; border-radius: 999px; background: var(--fill); color: var(--text-muted); font-size: 10.5px; font-weight: 600; }
.tag-ok { background: rgba(35, 122, 75, 0.1); color: #1d6840; }
.tag-warn { background: rgba(165, 104, 20, 0.11); color: #83520f; }
.tag-bad { background: var(--accent-soft); color: var(--accent-deep); }
.tag-muted { background: var(--fill); color: var(--text-muted); }
.req { padding: 1px 4px; border-radius: 3px; background: rgba(184, 65, 50, 0.12); font-size: 8px; font-style: normal; }
.sub-label, .soft-note { margin: 14px 0 7px; color: var(--text-faint); font-size: 9.5px; line-height: 1.65; }
.soft-note { margin: 10px 0 0; }
.muted-copy, .empty-state { margin: 16px 0 0; color: var(--text-faint); font-size: 11px; line-height: 1.7; }
.inline-tip { margin: 14px 0 0; padding: 10px 12px; border-radius: 9px; background: var(--accent-soft); color: var(--accent-deep); font-size: 10px; line-height: 1.65; }
.dimension-section, .suggestion-section { margin-bottom: 16px; padding: 28px; border: 1px solid var(--line-soft); background: var(--surface); }
.dimension-title, .suggestion-title { align-items: flex-end; padding-bottom: 20px; border-bottom: 1px solid var(--line-soft); }
.dimension-title > p { max-width: 430px; margin: 0; color: var(--text-faint); font-size: 10px; line-height: 1.65; text-align: right; }
.dimension-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0; }
.dimension-item { min-height: 150px; padding: 20px 22px; border-bottom: 1px solid var(--line-soft); border-right: 1px solid var(--line-soft); }
.dimension-item:nth-child(3n) { border-right: 0; }
.dimension-item:nth-last-child(-n+3) { border-bottom: 0; }
.dimension-top { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.dimension-top h3 { margin: 0; color: var(--text); font-size: 12px; }
.dimension-top strong { font-size: 13px; font-variant-numeric: tabular-nums; }
.dimension-meta { margin-top: 6px; color: var(--text-faint); font-size: 8.5px; }
.dimension-item p { margin: 9px 0 0; color: var(--text-muted); font-size: 10px; line-height: 1.65; }
.suggestion-list { display: flex; flex-direction: column; }
.suggestion-item { display: grid; grid-template-columns: 42px 1fr; gap: 18px; padding: 22px 0; border-bottom: 1px solid var(--line-soft); }
.suggestion-item:last-child { border-bottom: 0; padding-bottom: 2px; }
.suggestion-index { padding-top: 3px; color: var(--accent); font-family: var(--font-mono); font-size: 9px; font-weight: 700; }
.suggestion-head { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
.suggestion-section-name { color: var(--text-faint); font-size: 9.5px; }
.suggestion-head strong { flex: 1; min-width: 220px; color: var(--text); font-size: 12.5px; line-height: 1.55; }
.evidence-row, .action-row { display: grid; grid-template-columns: 64px 1fr; gap: 10px; margin-top: 11px; padding: 10px 12px; border-radius: 9px; }
.evidence-row { border: 1px solid var(--line-soft); background: var(--surface-deep); }
.action-row { background: var(--brand-soft); }
.evidence-row > span, .action-row > span { color: var(--text-faint); font-size: 9px; font-weight: 700; }
.action-row > span { color: var(--brand); }
.evidence-row p, .action-row p { margin: 0; color: var(--text-muted); font-size: 10.5px; line-height: 1.7; }
.action-bar { display: flex; align-items: center; justify-content: space-between; gap: 30px; margin-top: 24px; padding: 22px 24px; border: 1px solid var(--line); border-radius: var(--radius-lg); background: var(--surface); }
.action-copy { display: flex; flex-direction: column; gap: 5px; }
.action-copy strong { color: var(--text); font-family: var(--font-serif); font-size: 18px; }
.action-buttons { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 9px; }
.action-buttons :deep(.el-button) { margin-left: 0; }
@media (max-width: 1040px) {
  .overview { grid-template-columns: 1fr 1fr; }
  .priority-card { grid-column: 1 / -1; min-height: 0; }
  .priority-list { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
  .priority-list li { border-bottom: 0; border-right: 1px solid var(--line-soft); padding-right: 14px; }
  .priority-list li:last-child { border-right: 0; }
  .dimension-grid { grid-template-columns: 1fr 1fr; }
  .dimension-item:nth-child(3n) { border-right: 1px solid var(--line-soft); }
  .dimension-item:nth-child(2n) { border-right: 0; }
  .dimension-item:nth-last-child(-n+3) { border-bottom: 1px solid var(--line-soft); }
  .dimension-item:nth-last-child(-n+2) { border-bottom: 0; }
}
@media (max-width: 880px) {
  .report-head { grid-template-columns: 1fr; gap: 26px; }
  .report-target { max-width: 520px; }
  .overview { grid-template-columns: 1fr; }
  .priority-card { grid-column: auto; }
  .priority-list { display: flex; }
  .priority-list li { border-right: 0; border-bottom: 1px solid var(--line-soft); padding-right: 0; }
  .priority-list li:last-child { border-bottom: 0; }
  .evidence-grid { grid-template-columns: 1fr; }
  .dimension-grid { grid-template-columns: 1fr; }
  .dimension-item, .dimension-item:nth-child(2n), .dimension-item:nth-child(3n) { border-right: 0; border-bottom: 1px solid var(--line-soft); }
  .dimension-item:last-child { border-bottom: 0; }
  .action-bar { align-items: stretch; flex-direction: column; }
  .action-buttons { justify-content: flex-start; }
}
@media (max-width: 620px) {
  .report-head { padding-top: 8px; }
  .report-head h1 { font-size: clamp(38px, 12vw, 50px); }
  .score-card { min-height: 300px; }
  .score-value strong { font-size: 72px; }
  .map-card, .priority-card, .paper-card, .dimension-section, .suggestion-section, .summary-card { padding: 20px; }
  .summary-card { align-items: flex-start; }
  .summary-mark { width: 36px; height: 36px; }
  .dimension-title { align-items: flex-start; flex-direction: column; gap: 10px; }
  .dimension-title > p { text-align: left; }
  .suggestion-item { grid-template-columns: 28px 1fr; gap: 11px; }
  .evidence-row, .action-row { grid-template-columns: 1fr; gap: 5px; }
  .action-buttons { flex-direction: column; }
  .action-buttons :deep(.el-button) { width: 100%; }
}
</style>
