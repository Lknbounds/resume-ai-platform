<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  分析页。★ 参考 getresumeiq.com/ats-resume-checker 的布局：
 *
 *      ┌───────────────────────────────────────────────┐
 *      │  标题：检查你的简历与目标岗位的匹配度            │
 *      │  副标题：一分钟内得到分数、关键词差距与改进方向   │
 *      ├──────────────────┬────────────────────────────┤
 *      │  左边：选简历     │  右边：选/粘贴 JD          │
 *      │  （或粘贴新内容）  │                            │
 *      ├──────────────────┴────────────────────────────┤
 *      │                  [开始分析]                    │
 *      └───────────────────────────────────────────────┘
 *
 *  ★ 为什么左右分栏？
 *    分析的本质是"简历 × JD"的比对。让两个输入并排出现，
 *    用户能直观理解"系统在比较这两样东西"。
 *    ResumeIQ 就是这么布局的。
 * ═══════════════════════════════════════════════════════════════════════
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { resumeApi } from '@/api/resume'
import { jdApi } from '@/api/jd'
import { analysisApi } from '@/api/analysis'

const router = useRouter()
const route = useRoute()

const loading = ref(true)
const submitting = ref(false)

/** 我的简历列表（扁平化成"简历 - vN"的可选项） */
const resumeOptions = ref([])
/** 我的 JD 列表 */
const jdOptions = ref([])

/** 选中项 */
const selectedVersionId = ref(null)
const selectedJdId = ref(null)

/** 粘贴 JD 的弹窗 */
const jdDialogVisible = ref(false)
const jdSaving = ref(false)
const jdForm = ref({ title: '', company: '', content: '' })

onMounted(async () => {
  try {
    /*
     * ★ 并行拉两个列表：
     *   Promise.all 让两个请求同时发出，而不是等第一个回来再发第二个。
     *   省掉一次网络往返。
     */
    const [resumes, jds] = await Promise.all([
      resumeApi.list({ page: 1, size: 50 }),
      jdApi.list({ page: 1, size: 50 })
    ])

    // 把简历展开成"每个版本一个选项"
    resumeOptions.value = resumes.records.flatMap((r) =>
      // 只给"当前版本"创建选项 —— 用户分析时通常用最新版
      // （要分析旧版本可以在简历详情页操作，这里先简化）
      [{
        value: r.currentVersionId,
        label: r.title,
        sub: `v${r.versionCount}`,
        position: r.targetPosition,
        resumeId: r.id
      }]
    )

    jdOptions.value = (jds.records || [])
        .filter((jd) => jd.parsed && jd.parsed.requiredSkills?.length > 0)
        .map((jd) => ({
          value: jd.id,
          label: jd.title,
          sub: `${jd.parsed.requiredSkills.length} 项技能要求`,
          company: jd.company
        }))

    // 默认选中：URL 参数优先，否则第一个
    selectedVersionId.value = Number(route.query.versionId) || resumeOptions.value[0]?.value || null
    selectedJdId.value = Number(route.query.jdId) || jdOptions.value[0]?.value || null
  } finally {
    loading.value = false
  }
})

const canSubmit = computed(() => !!selectedVersionId.value && !!selectedJdId.value)

/**
 * 开始分析。
 */
async function analyze() {
  if (!canSubmit.value) {
    return ElMessage.warning('请选择简历和岗位')
  }

  submitting.value = true
  try {
    const report = await analysisApi.create({
      resumeVersionId: selectedVersionId.value,
      jdId: selectedJdId.value
    })
    // 跳到报告页
    router.push(`/analysis/${report.id}`)
  } finally {
    submitting.value = false
  }
}

/**
 * 保存新 JD（从分析页直接粘贴，不用先跳去 JD 管理）。
 *
 * ★ 为什么要有这个入口？
 *   ResumeIQ 的分析页就是"左边简历、右边粘贴 JD"，一步到位。
 *   如果必须先跳去 JD 页保存、再回来分析，多两次跳转。
 */
async function saveJd() {
  if (!jdForm.value.title.trim()) return ElMessage.warning('请填写岗位名称')
  if (jdForm.value.content.replace(/\s/g, '').length < 30) {
    return ElMessage.warning('JD 内容至少 30 字')
  }

  jdSaving.value = true
  try {
    const jd = await jdApi.create({
      title: jdForm.value.title.trim(),
      company: jdForm.value.company.trim() || null,
      content: jdForm.value.content
    })

    // 加进下拉列表并选中
    jdOptions.value.unshift({
      value: jd.id,
      label: jd.title,
      sub: `${jd.parsed?.requiredSkills?.length || 0} 项技能要求`,
      company: jd.company
    })
    selectedJdId.value = jd.id

    jdDialogVisible.value = false
    jdForm.value = { title: '', company: '', content: '' }
    ElMessage.success('JD 已保存并解析')
  } finally {
    jdSaving.value = false
  }
}

/** 缺少简历时的引导 */
function goCreateResume() {
  router.push('/resumes')
}
</script>

<template>
  <DefaultLayout>
    <header class="head">
      <div class="head-copy">
        <p class="eyebrow"><span /> ANALYSIS DESK</p>
        <h1>把简历和岗位，<br />放到同一张桌上。</h1>
        <p class="sub">
          选一份简历和一个目标岗位，得到匹配分数、关键词差距，以及按板块整理的改进方向。
        </p>
      </div>

      <aside class="head-note" aria-label="分析流程">
        <p>一次完整的对照</p>
        <div class="note-row">
          <span>简历</span>
          <i aria-hidden="true" />
          <span>岗位</span>
          <i aria-hidden="true" />
          <span>下一步</span>
        </div>
        <small>系统会把两侧要求逐项对齐，并标出已有证据。</small>
      </aside>
    </header>

    <div v-if="loading" class="loading-panel">
      <el-skeleton :rows="7" animated />
    </div>

    <template v-else>
      <div class="pickers">
        <section class="picker">
          <div class="picker-head">
            <div class="picker-heading">
              <span class="step">01</span>
              <div>
                <span class="label">MATERIAL A</span>
                <span class="picker-title">选择简历</span>
              </div>
            </div>
            <span class="picker-status">{{ resumeOptions.length }} 个可选版本</span>
          </div>

          <div v-if="resumeOptions.length === 0" class="picker-empty">
            <span class="empty-mark">A</span>
            <p>还没有可分析的简历</p>
            <el-button size="small" @click="goCreateResume">去新建简历</el-button>
          </div>

          <div v-else class="options">
            <button
              v-for="o in resumeOptions"
              :key="o.value"
              type="button"
              class="option"
              :class="{ active: selectedVersionId === o.value }"
              :aria-pressed="selectedVersionId === o.value"
              @click="selectedVersionId = o.value"
            >
              <span class="option-copy">
                <span class="option-label">{{ o.label }}</span>
                <span class="option-meta">
                  {{ o.sub }}
                  <template v-if="o.position"> · {{ o.position }}</template>
                </span>
              </span>
              <span class="option-check" aria-hidden="true">{{ selectedVersionId === o.value ? '已选' : '选择' }}</span>
            </button>
          </div>
        </section>

        <section class="picker">
          <div class="picker-head">
            <div class="picker-heading">
              <span class="step">02</span>
              <div>
                <span class="label">MATERIAL B</span>
                <span class="picker-title">选择目标岗位</span>
              </div>
            </div>
            <el-button text size="small" class="add-btn" @click="jdDialogVisible = true">
              + 粘贴新 JD
            </el-button>
          </div>

          <div v-if="jdOptions.length === 0" class="picker-empty">
            <span class="empty-mark">B</span>
            <p>还没有可用的岗位描述</p>
            <el-button size="small" @click="jdDialogVisible = true">粘贴一份</el-button>
          </div>

          <div v-else class="options">
            <button
              v-for="o in jdOptions"
              :key="o.value"
              type="button"
              class="option"
              :class="{ active: selectedJdId === o.value }"
              :aria-pressed="selectedJdId === o.value"
              @click="selectedJdId = o.value"
            >
              <span class="option-copy">
                <span class="option-label">{{ o.label }}</span>
                <span class="option-meta">
                  {{ o.sub }}
                  <template v-if="o.company"> · {{ o.company }}</template>
                </span>
              </span>
              <span class="option-check" aria-hidden="true">{{ selectedJdId === o.value ? '已选' : '选择' }}</span>
            </button>
          </div>
        </section>
      </div>

      <div class="submit-row">
        <div class="submit-copy">
          <span class="submit-label">READY WHEN YOU ARE</span>
          <strong>{{ canSubmit ? '两边都选好了，可以开始。' : '还差一份材料。' }}</strong>
          <p v-if="!canSubmit" class="hint">需要同时选好简历和岗位才能开始。</p>
        </div>
        <el-button
          type="primary"
          size="large"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="analyze"
        >
          开始分析
        </el-button>
      </div>
    </template>

    <el-dialog v-model="jdDialogVisible" title="粘贴岗位要求" width="680px" top="8vh">
      <el-form label-position="top">
        <div class="row2">
          <el-form-item label="岗位名称">
            <el-input v-model="jdForm.title" placeholder="如：Java 后端开发工程师" maxlength="100" />
          </el-form-item>
          <el-form-item label="公司（选填）">
            <el-input v-model="jdForm.company" placeholder="如：某科技" maxlength="100" />
          </el-form-item>
        </div>

        <el-form-item label="JD 内容">
          <el-input
            v-model="jdForm.content"
            type="textarea"
            :rows="14"
            placeholder="把岗位描述粘贴到这里&#10;&#10;包含任职要求部分的 JD 解析效果最好"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="jdDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="jdSaving" @click="saveJd">
          保存并解析
        </el-button>
      </template>
    </el-dialog>
  </DefaultLayout>
</template>

<style scoped>
.head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 350px;
  align-items: end;
  gap: clamp(36px, 7vw, 92px);
  padding: 20px 0 52px;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0 0 18px;
  color: var(--brand);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.19em;
}

.eyebrow span {
  width: 22px;
  height: 1px;
  background: var(--accent);
}

.title,
h1 {
  margin: 0;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: clamp(38px, 4.8vw, 58px);
  line-height: 1.16;
  letter-spacing: -0.05em;
}

.sub {
  max-width: 580px;
  margin: 20px 0 0;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.85;
}

.head-note {
  padding: 22px 24px;
  border-radius: var(--radius-lg);
  background: var(--brand-soft);
  border: 1px solid rgba(31, 75, 62, 0.1);
}

.head-note > p {
  margin: 0 0 15px;
  color: var(--brand-deep);
  font-family: var(--font-serif);
  font-size: 17px;
  font-weight: 700;
}

.note-row {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--text);
  font-size: 10px;
  font-weight: 700;
}

.note-row i {
  flex: 1;
  height: 1px;
  background: rgba(31, 75, 62, 0.22);
}

.head-note small {
  display: block;
  margin-top: 13px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.65;
}

.loading-panel,
.pickers {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}

.loading-panel {
  grid-template-columns: 1fr;
  padding: 26px;
  border-radius: var(--radius-lg);
  background: var(--surface);
  border: 1px solid var(--line-soft);
}

.picker {
  position: relative;
  min-height: 380px;
  padding: 24px;
  border: 1px solid var(--line);
  border-radius: 4px 20px 20px 20px;
  background:
    linear-gradient(rgba(255, 252, 247, 0.95), rgba(255, 252, 247, 0.95)),
    repeating-linear-gradient(0deg, transparent 0 31px, rgba(112, 95, 70, 0.07) 31px 32px);
  box-shadow: var(--shadow-sm);
}

.picker::before {
  content: '';
  position: absolute;
  top: -1px;
  left: 28px;
  width: 72px;
  height: 3px;
  background: var(--accent);
}

.picker:nth-child(2)::before { background: var(--mist-blue); }

.picker-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  min-height: 50px;
  padding-bottom: 18px;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--line-soft);
}

.picker-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}

.step {
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
}

.picker-heading > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.label {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.15em;
}

.picker-title {
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 20px;
  font-weight: 700;
}

.picker-status {
  color: var(--text-faint);
  font-size: 10px;
  padding-top: 9px;
}

.add-btn {
  color: var(--brand) !important;
  font-size: 11px;
}

.picker-empty {
  display: flex;
  min-height: 230px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  color: var(--text-faint);
  text-align: center;
}

.empty-mark {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  border: 1px dashed var(--line);
  color: var(--text-faint);
  font-family: var(--font-serif);
}

.picker-empty p { margin: 0; font-size: 12px; }

.options {
  display: flex;
  flex-direction: column;
  gap: 9px;
  max-height: 300px;
  padding-right: 4px;
  overflow-y: auto;
}

.option {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  min-height: 66px;
  padding: 12px 14px;
  border: 1px solid var(--line-soft);
  border-radius: 12px;
  background: rgba(255, 252, 247, 0.84);
  color: var(--text);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, background-color 0.18s ease,
              transform 0.18s ease;
}

.option:hover {
  border-color: #c8bbaa;
  background: var(--surface);
  transform: translateY(-1px);
}

.option.active {
  border-color: rgba(31, 75, 62, 0.42);
  background: var(--brand-soft);
  box-shadow: inset 3px 0 0 var(--brand);
}

.option-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.option-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
}

.option-meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-faint);
  font-size: 10.5px;
}

.option-check {
  flex-shrink: 0;
  color: var(--text-faint);
  font-size: 10px;
}

.option.active .option-check {
  color: var(--brand);
  font-weight: 700;
}

.submit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  margin-top: 18px;
  padding: 22px 24px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--line-soft);
  background: var(--surface);
}

.submit-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.submit-label {
  color: var(--accent-deep);
  font-family: var(--font-mono);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.submit-copy strong {
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 17px;
}

.hint {
  margin: 0;
  color: var(--text-faint);
  font-size: 11px;
}

.submit-row :deep(.el-button) {
  min-width: 170px;
  min-height: 48px;
}

.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

@media (max-width: 900px) {
  .head { grid-template-columns: 1fr; gap: 28px; }
  .head-note { max-width: 520px; }
}

@media (max-width: 760px) {
  .head { padding-top: 8px; padding-bottom: 36px; }
  .pickers { grid-template-columns: 1fr; }
  .picker { min-height: 320px; padding: 20px; }
  .picker-head { flex-wrap: wrap; }
  .submit-row { align-items: stretch; flex-direction: column; gap: 16px; }
  .submit-row :deep(.el-button) { width: 100%; }
  .row2 { grid-template-columns: 1fr; }
}
</style>
