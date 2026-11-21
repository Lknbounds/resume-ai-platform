<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { resumeApi } from '@/api/resume'
import { analysisApi } from '@/api/analysis'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  resumeId: { type: Number, default: null },
  resumeTitle: { type: String, default: '简历' }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const analysisLoading = ref(false)
const detail = ref(null)
const version = ref(null)
const analysisReport = ref(null)
const activeVersionId = ref(null)
const draft = ref('')
const changeNote = ref('')
const editorRef = ref(null)

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const suggestions = computed(() => {
  const reportSuggestions = analysisReport.value?.suggestions || []
  if (analysisReport.value) {
    return reportSuggestions.map((item) => ({
      title: item.issue || '修改建议',
      tag: `${severityLabel[item.severity] || '建议'} · ${sectionLabel[item.section] || item.section || '内容'}`,
      detail: [item.suggestion, item.evidence ? `证据：${item.evidence}` : ''].filter(Boolean).join(' '),
      action: '定位正文'
    }))
  }
  return buildSuggestions(draft.value || '', detail.value?.targetPosition || '')
})

const suggestionSummary = computed(() => {
  if (!analysisReport.value) {
    return '当前版本还没有分析报告。下面先显示基础修改检查；完成分析后会自动替换为正式分析建议。'
  }
  return analysisReport.value.summary || '以下内容与分析报告中的修改建议保持一致。'
})

const severityLabel = {
  HIGH: '优先改',
  MEDIUM: '建议改',
  LOW: '可选'
}

const sectionLabel = {
  SUMMARY: '自我评价',
  SKILLS: '技能',
  EXPERIENCE: '工作经历',
  PROJECT: '项目',
  EDUCATION: '教育',
  KEYWORDS: '关键词',
  OTHER: '其他'
}

watch(() => props.modelValue, async (open) => {
  if (open && props.resumeId) {
    await loadWorkspace()
  }
}, { immediate: true })

async function loadWorkspace() {
  loading.value = true
  try {
    detail.value = await resumeApi.detail(props.resumeId)
    activeVersionId.value = detail.value.currentVersionId || detail.value.versions?.[0]?.id
    if (activeVersionId.value) {
      await loadVersion(activeVersionId.value)
    }
  } finally {
    loading.value = false
  }
}

async function loadVersion(versionId) {
  activeVersionId.value = versionId
  const [versionData] = await Promise.all([
    resumeApi.version(versionId),
    loadAnalysis(versionId)
  ])
  version.value = versionData
  draft.value = versionData.content || ''
  changeNote.value = ''
  await nextTick()
  editorRef.value?.focus()
}

async function loadAnalysis(versionId) {
  analysisLoading.value = true
  analysisReport.value = null
  try {
    const page = await analysisApi.list({
      resumeVersionId: versionId,
      page: 1,
      size: 1
    })
    const latest = page.records?.[0]
    analysisReport.value = latest ? await analysisApi.detail(latest.id) : null
  } catch {
    analysisReport.value = null
  } finally {
    analysisLoading.value = false
  }
}

async function refreshAnalysis() {
  if (!activeVersionId.value) return
  await loadAnalysis(activeVersionId.value)
  ElMessage.success(analysisReport.value ? '已同步最新分析建议' : '该版本还没有分析报告')
}

function goAnalysis() {
  visible.value = false
  router.push('/analysis/new')
}

async function save() {
  if (draft.value.replace(/\s/g, '').length < 50) {
    ElMessage.warning('正文至少 50 字')
    return
  }
  if (draft.value === version.value?.content) {
    ElMessage.warning('内容没有变化，未创建新版本')
    return
  }

  saving.value = true
  try {
    await resumeApi.addVersion(props.resumeId, {
      content: draft.value,
      changeNote: changeNote.value.trim() || '工作台编辑',
      source: 'EDIT',
      parentVersionId: activeVersionId.value
    })
    ElMessage.success('已保存为新版本，请重新分析后同步最新建议')
    emit('changed')
    await loadWorkspace()
  } finally {
    saving.value = false
  }
}

function locate() {
  editorRef.value?.focus()
}

function normalizeSpacing() {
  const before = draft.value
  draft.value = before
    .replace(/[\u4e00-\u9fa5]\s+[\u4e00-\u9fa5]/g, (match) => match.replace(/\s+/g, ''))
    .replace(/[ \t\u3000]{2,}/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
  if (draft.value !== before) {
    ElMessage.success('已清理多余空格和断行')
  }
}

function buildSuggestions(text, targetPosition) {
  const list = []
  const headings = ['教育经历', '个人优势', '项目经历', '校园经历', '自我评价']
  const positions = headings
    .map((heading) => ({ heading, index: text.indexOf(heading) }))
    .filter((item) => item.index >= 0)
    .sort((a, b) => a.index - b.index)

  const canonical = ['教育经历', '个人优势', '项目经历', '校园经历', '自我评价']
  const actualOrder = positions.map((item) => item.heading)
  const expectedOrder = canonical.filter((heading) => actualOrder.includes(heading))
  if (actualOrder.join('>') !== expectedOrder.join('>')) {
    list.push({
      title: '调整板块顺序',
      tag: '结构',
      detail: `建议按“教育经历 → 个人优势 → 项目经历 → 校园经历 → 自我评价”排列。当前识别到：${actualOrder.join(' → ') || '未识别到完整标题'}。`,
      action: '定位正文'
    })
  }

  const courseCount = (text.match(/主修课程/g) || []).length
  if (courseCount > 1) {
    list.push({
      title: '合并重复的主修课程',
      tag: '重复',
      detail: '本科和硕士课程都用了“主修课程”，建议改成“本科核心课程”和“硕士核心课程”，避免 ATS 把它们识别为重复内容。',
      action: '定位正文'
    })
  }

  const brokenSpacing = text.match(/[\u4e00-\u9fa5]\s+[\u4e00-\u9fa5]/g) || []
  if (brokenSpacing.length >= 2) {
    list.push({
      title: '清理解析断行',
      tag: '格式',
      detail: `发现 ${brokenSpacing.length} 处中文之间被空格拆开，例如“${brokenSpacing.slice(0, 2).join('、')}”。这些会影响关键词识别。`,
      action: '一键清理'
    })
  }

  const metricMatches = text.match(/\d+(?:\.\d+)?\s*(?:%|倍|人|个|场|天|小时|秒|ms|QPS|万|GB|MB)/gi) || []
  if (metricMatches.length < 4) {
    list.push({
      title: '补充量化结果',
      tag: '结果',
      detail: '建议每个项目至少补一项真实指标，例如响应时间、并发量、准确率、用户量、交付周期或节省的人力。',
      action: '定位正文'
    })
  }

  const projectCount = (text.match(/项目描述/g) || []).length
  if (projectCount >= 2) {
    list.push({
      title: '拆清项目边界',
      tag: '项目',
      detail: '建议每个项目固定使用“项目名称 / 时间 / 技术栈 / 项目描述 / 个人职责 / 项目成果”结构。',
      action: '定位正文'
    })
  }

  if (targetPosition) {
    list.push({
      title: `围绕“${targetPosition}”调整关键词`,
      tag: '岗位',
      detail: '把与目标岗位最相关的技术、职责和成果放到项目描述前部，不相关经历可以压缩。',
      action: '定位正文'
    })
  }

  list.push({
    title: '核对事实与措辞',
    tag: '检查',
    detail: '逐条确认时间、学校、项目名称、技术栈和量化数据都是真实的，并统一使用“负责、设计、实现、优化”等动作动词。',
    action: '定位正文'
  })

  return list.slice(0, 7)
}
</script>

<template>
  <el-dialog
    v-model="visible"
    class="resume-workspace-dialog"
    width="94vw"
    top="3vh"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <template #header>
      <div class="workspace-header">
        <div>
          <div class="workspace-eyebrow">RESUME EDITOR</div>
          <h2>{{ resumeTitle }}</h2>
          <p>左边修改正文，右边对照建议逐条优化</p>
        </div>
        <div class="workspace-meta">
          <el-select
            v-if="detail?.versions?.length"
            v-model="activeVersionId"
            size="small"
            class="version-select"
            @change="loadVersion"
          >
            <el-option
              v-for="item in detail.versions"
              :key="item.id"
              :label="`v${item.versionNo} · ${item.charCount} 字`"
              :value="item.id"
            />
          </el-select>
          <span>{{ draft.replace(/\s/g, '').length }} 字</span>
          <span v-if="detail?.targetPosition">{{ detail.targetPosition }}</span>
        </div>
      </div>
    </template>

    <div v-if="loading" class="workspace-loading">
      <el-skeleton :rows="14" animated />
    </div>

    <div v-else class="workspace-grid">
      <section class="workspace-pane editor-pane">
        <div class="pane-head">
          <div>
            <span class="pane-kicker">WORD 风格正文</span>
            <h3>可编辑版本</h3>
          </div>
          <div class="editor-actions">
            <el-button size="small" @click="normalizeSpacing">清理格式</el-button>
            <el-button
              type="primary"
              size="small"
              :loading="saving"
              :disabled="draft === version?.content"
              @click="save"
            >
              保存新版本
            </el-button>
          </div>
        </div>

        <textarea
          ref="editorRef"
          v-model="draft"
          class="word-editor"
          spellcheck="false"
          @keydown.ctrl.s.prevent="save"
        ></textarea>

        <div class="editor-foot">
          <el-input
            v-model="changeNote"
            size="small"
            maxlength="255"
            placeholder="变更说明（可选）"
          />
          <span class="editor-hint">Ctrl + S 保存</span>
        </div>
      </section>

      <section class="workspace-pane suggestion-pane">
        <div class="pane-head">
          <div>
            <span class="pane-kicker">REVIEW</span>
            <h3>修改建议</h3>
          </div>
          <div class="suggestion-actions">
            <el-button size="small" :loading="analysisLoading" @click="refreshAnalysis">刷新分析</el-button>
            <el-button v-if="!analysisReport" size="small" type="primary" plain @click="goAnalysis">去分析</el-button>
            <span class="suggestion-count">{{ suggestions.length }} 条</span>
          </div>
        </div>

        <div class="suggestion-summary">
          <strong>{{ analysisReport ? `分析报告 · ${analysisReport.overallScore?.toFixed(1) ?? '—'} 分` : '基础修改检查' }}</strong>
          <p>{{ suggestionSummary }}</p>
        </div>

        <div class="suggestion-list">
          <article v-for="item in suggestions" :key="item.title" class="suggestion-item">
            <div class="suggestion-top">
              <strong>{{ item.title }}</strong>
              <span>{{ item.tag }}</span>
            </div>
            <p>{{ item.detail }}</p>
            <el-button
              text
              type="primary"
              size="small"
              @click="item.action === '一键清理' ? normalizeSpacing() : locate()"
            >
              {{ item.action }}
            </el-button>
          </article>
        </div>
      </section>
    </div>
  </el-dialog>
</template>

<style scoped>
.workspace-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}

.workspace-eyebrow,
.pane-kicker {
  color: var(--brand-deep);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0;
}

.workspace-header h2 {
  margin: 4px 0 0;
  color: var(--text);
  font-size: 22px;
  letter-spacing: 0;
}

.workspace-header p {
  margin: 5px 0 0;
  color: var(--text-muted);
  font-size: 12px;
}

.workspace-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 7px;
  color: var(--text-muted);
  font-size: 12px;
}

.workspace-meta span {
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--fill);
}

.workspace-loading {
  min-height: 650px;
  padding: 12px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(560px, 1.45fr) minmax(340px, 0.75fr);
  gap: 14px;
  min-height: 680px;
}

.workspace-pane {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--line-soft);
  border-radius: 8px;
  background: var(--surface);
}

.pane-head {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 15px;
  border-bottom: 1px solid var(--line-soft);
}

.pane-head h3 {
  margin: 3px 0 0;
  color: var(--text);
  font-size: 15px;
  letter-spacing: 0;
}

.editor-actions {
  display: flex;
  gap: 7px;
}

.editor-pane {
  display: flex;
  flex-direction: column;
}

.word-editor {
  flex: 1;
  width: 100%;
  min-height: 535px;
  padding: 32px 38px;
  border: 0;
  outline: 0;
  resize: none;
  background:
    linear-gradient(to bottom, transparent 31px, rgba(80, 94, 112, 0.07) 32px) 0 0 / 100% 32px,
    #fff;
  color: var(--text);
  font-family: 'Times New Roman', 'Songti SC', SimSun, serif;
  font-size: 15px;
  line-height: 2.1;
}

.word-editor:focus {
  box-shadow: inset 0 0 0 2px rgba(41, 163, 153, 0.22);
}

.editor-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 13px;
  border-top: 1px solid var(--line-soft);
}

.editor-hint {
  flex: 0 0 auto;
  color: var(--text-faint);
  font-size: 11px;
}

.suggestion-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.version-select {
  min-width: 150px;
}

.suggestion-count {
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 12px;
}

.suggestion-summary {
  margin: 13px 13px 0;
  padding: 13px;
  border-left: 3px solid var(--brand);
  background: var(--brand-soft);
}

.suggestion-summary strong {
  color: var(--brand-deep);
  font-size: 13px;
}

.suggestion-summary p {
  margin: 5px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.7;
}

.suggestion-list {
  display: grid;
  gap: 10px;
  padding: 13px;
}

.suggestion-item {
  padding: 13px;
  border: 1px solid var(--line-soft);
  border-radius: 7px;
  background: var(--bg);
}

.suggestion-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.suggestion-top strong {
  color: var(--text);
  font-size: 14px;
}

.suggestion-top span {
  padding: 3px 6px;
  border-radius: 5px;
  background: var(--surface);
  color: var(--text-muted);
  font-size: 11px;
}

.suggestion-item p {
  margin: 8px 0 4px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.75;
}

:deep(.resume-workspace-dialog) {
  max-width: 1500px;
}

:deep(.resume-workspace-dialog .el-dialog__body) {
  padding: 16px;
}
</style>