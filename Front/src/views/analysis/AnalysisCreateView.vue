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
      jdApi.list()
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

    jdOptions.value = jds
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
    <!-- ═══════════ 标题区 ═══════════ -->
    <header class="head">
      <h1 class="title">检查简历与岗位的匹配度</h1>
      <p class="sub">
        一分钟内得到匹配分数、关键词差距和按板块的改进方向。
      </p>
    </header>

    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else>
      <!-- ═══════════ 左右两栏 ═══════════ -->
      <div class="pickers">
        <!-- ───── 左：简历 ───── -->
        <section class="picker">
          <div class="picker-head">
            <span class="step">01</span>
            <span class="picker-title">选择简历</span>
          </div>

          <div v-if="resumeOptions.length === 0" class="picker-empty">
            <p>还没有简历</p>
            <el-button size="small" @click="goCreateResume">去新建</el-button>
          </div>

          <div v-else class="options">
            <button
              v-for="o in resumeOptions"
              :key="o.value"
              class="option"
              :class="{ active: selectedVersionId === o.value }"
              @click="selectedVersionId = o.value"
            >
              <span class="option-label">{{ o.label }}</span>
              <span class="option-meta">
                {{ o.sub }}
                <template v-if="o.position"> · {{ o.position }}</template>
              </span>
            </button>
          </div>
        </section>

        <!-- ───── 右：JD ───── -->
        <section class="picker">
          <div class="picker-head">
            <span class="step">02</span>
            <span class="picker-title">选择目标岗位</span>
            <el-button text size="small" class="add-btn" @click="jdDialogVisible = true">
              + 粘贴新 JD
            </el-button>
          </div>

          <div v-if="jdOptions.length === 0" class="picker-empty">
            <p>还没有可用的 JD</p>
            <el-button size="small" @click="jdDialogVisible = true">粘贴一份</el-button>
          </div>

          <div v-else class="options">
            <button
              v-for="o in jdOptions"
              :key="o.value"
              class="option"
              :class="{ active: selectedJdId === o.value }"
              @click="selectedJdId = o.value"
            >
              <span class="option-label">{{ o.label }}</span>
              <span class="option-meta">
                {{ o.sub }}
                <template v-if="o.company"> · {{ o.company }}</template>
              </span>
            </button>
          </div>
        </section>
      </div>

      <!-- ═══════════ 提交 ═══════════ -->
      <div class="submit-row">
        <el-button
          type="primary"
          size="large"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="analyze"
        >
          开始分析
        </el-button>
        <p v-if="!canSubmit" class="hint">
          需要同时选好简历和岗位才能开始
        </p>
      </div>
    </template>

    <!-- ═══════════ 粘贴 JD 弹窗 ═══════════ -->
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
/* ═══════════ 标题 ═══════════ */

.head {
  text-align: center;
  padding: 24px 0 40px;
}

.title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.025em;
  color: var(--text);
  margin: 0 0 10px;
}

.sub {
  font-size: 14px;
  color: var(--text-muted);
  margin: 0;
}

/* ═══════════ 两栏 ═══════════ */

.pickers {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.picker {
  background: var(--surface);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  padding: 18px;
  min-height: 200px;
}

.picker-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

/*
 * ★ 步骤号用品牌色小方块 —— 提示"这是流程的第一步"，
 *   比纯文字标题更有指引感。
 */
.step {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 11px;
  font-weight: 700;
}

.picker-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.add-btn {
  margin-left: auto;
  color: var(--brand);
  font-size: 12px;
}

.picker-empty {
  text-align: center;
  padding: 32px 0;
  color: var(--text-faint);
  font-size: 13px;
}

.picker-empty p {
  margin: 0 0 12px;
}

/* ═══════════ 选项列表 ═══════════ */

.options {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 320px;
  overflow-y: auto;
}

/*
 * ★ 用 <button> 而不是 <div>：
 *   键盘可以 Tab 到、可以回车选中 —— 可访问性更好。
 */
.option {
  display: flex;
  flex-direction: column;
  gap: 3px;

  width: 100%;
  padding: 11px 13px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: var(--surface);

  text-align: left;
  cursor: pointer;
  font-family: inherit;

  transition: border-color 0.15s, background 0.15s;
}

.option:hover {
  border-color: #c3c8d1;
  background: #fafbfc;
}

/*
 * ★ 选中态：品牌色边框 + 浅青底
 *   同时用颜色和背景区分，不单靠颜色（色盲友好）
 */
.option.active {
  border-color: var(--brand);
  background: var(--brand-soft);
}

.option-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.option.active .option-label {
  color: var(--brand-deep);
}

.option-meta {
  font-size: 11px;
  color: var(--text-faint);
}

/* ═══════════ 提交 ═══════════ */

.submit-row {
  text-align: center;
  padding: 32px 0 8px;
}

.submit-row .el-button {
  min-width: 180px;
}

.hint {
  font-size: 12px;
  color: var(--text-faint);
  margin: 12px 0 0;
}

/* ═══════════ 弹窗 ═══════════ */

.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

/* ═══════════ 窄屏 ═══════════ */

@media (max-width: 760px) {
  .pickers {
    grid-template-columns: 1fr;
  }

  .title {
    font-size: 22px;
  }

  .row2 {
    grid-template-columns: 1fr;
  }
}
</style>
