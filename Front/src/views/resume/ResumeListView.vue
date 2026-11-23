<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  简历管理。★ 核心：一眼看出"每份简历投什么岗、多少分、改到第几版了"。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 视觉风格】
 *  参考 ResumeIQ 的卡片体系：
 *      白底卡片 + 细边框 + 14px 圆角 + hover 时轻微上浮 + 阴影
 *      分数用语义色（优秀绿 / 良好蓝 / 一般橙 / 偏低红）
 *
 *  【★ 数据流向】
 *      进入 → GET /api/resumes → 带 targetPosition 的列表
 *         ↓
 *      渲染成卡片
 *         ↓
 *      点卡片 → /resumes/{id}
 */
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import ResumeWorkspaceDialog from '@/components/ResumeWorkspaceDialog.vue'
import { useResumeStore } from '@/stores/resume'
import { resumeApi } from '@/api/resume'

const router = useRouter()
const store = useResumeStore()

const page = ref(1)
const size = ref(10)

async function loadList() {
  const data = await store.fetchList(page.value, size.value, keyword.value)
  if (data.records.length === 0 && data.total > 0 && page.value > 1) {
    page.value = Math.max(1, Math.min(page.value - 1, data.pages || 1))
    await store.fetchList(page.value, size.value, keyword.value)
  }
}

function onPage(nextPage) {
  page.value = nextPage
  loadList()
}

function onSizeChange(nextSize) {
  size.value = nextSize
  page.value = 1
  loadList()
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【搜索】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 为什么用防抖（debounce）而不是每次输入都搜？
 *   用户打 "Java" 会触发 4 次请求（J、Ja、Jav、Java）。
 *   防抖让"停止输入 300ms 后才发一次"。
 *
 * ★ 为什么不用 el-input 的 clearable + @clear 单独处理？
 *   清空时 keyword 变成 ''，watch 会自然触发一次"无关键词"的查询。
 *   不用额外写逻辑。
 */
const keyword = ref('')
let searchTimer = null

function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    loadList()
  }, 300)
}

function clearSearch() {
  keyword.value = ''
  clearTimeout(searchTimer)
  page.value = 1
  loadList()
}

const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({
  title: '',
  targetPosition: '',
  content: ''
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【文件上传状态】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 为什么单独一组状态，不塞进 form？
 *   因为这些是"上传过程"的临时状态，
 *   提交创建简历时不需要它们。
 *   分开之后语义清晰：form 是"最终要提交的数据"，
 *   下面这些是"上传这一步的 UI 状态"。
 */
const uploading = ref(false)
/** 提取质量报告，用于在弹窗顶部显示提示条 */
const extractQuality = ref(null)
/** 提取出的原始文件名，显示"已从 xxx.pdf 导入" */
const extractedFrom = ref('')

/** 电脑端三栏工作台 */
const workspaceVisible = ref(false)
const workspaceResume = ref(null)

/** 常用岗位建议，帮用户填出规范写法 */
const positionSuggestions = [
  'Java 后端开发工程师',
  '前端开发工程师',
  '全栈开发工程师',
  '数据分析师',
  '算法工程师',
  '测试工程师',
  '运维工程师',
  '产品经理',
  'UI 设计师'
]

/** 实时字数（和后端算法一致：去掉所有空白） */
const charCount = computed(() => form.content.replace(/\s/g, '').length)
const MIN_CHARS = 50
const enoughChars = computed(() => charCount.value >= MIN_CHARS)

onMounted(loadList)

function openCreate() {
  form.title = ''
  form.targetPosition = ''
  form.content = ''
  extractQuality.value = null
  extractedFrom.value = ''
  dialogVisible.value = true
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【处理文件上传】★ 只在"选了文件后"执行，不自动提交
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 【★ 和 ResumeIQ 的关键差异】
 *
 *   ResumeIQ：选了文件 → 上传 → 直接分析 → 出分
 *   我们：   选了文件 → 提取文本 → 【填进编辑框】→ 用户看一眼/改改 → 点创建
 *
 *   ★ 为什么要在中间停一步？
 *     PDF 解析出错是常态：扫描件读出空、双栏读出乱序、字体读出乱码。
 *     如果不让用户看到"读成了什么"，他会拿着一份残缺的简历
 *     得到一个假分数，还以为是自己简历差。
 *
 *   多一次确认，换的是"用户知道自己在分析什么"。
 */
async function handleFileChange(uploadFile) {
  const file = uploadFile.raw
  if (!file) return

  // ───── 前端先挡一层明显的问题 ─────
  // ★ 这只是体验优化，后端还会再校验一次（安全边界在服务端）
  const name = file.name.toLowerCase()
  if (!name.endsWith('.pdf') && !name.endsWith('.docx')) {
    ElMessage.warning('只支持 .pdf 和 .docx 文件')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 5MB')
    return false
  }


  uploading.value = true
  try {
    const result = await resumeApi.extract(file)

    /*
     * ★ REJECT：文本不可用，明确告诉用户原因，不填进编辑框。
     *
     *   为什么不"尽量填进去"？
     *     填进去一段乱码，用户可能没注意就点了创建，
     *     然后分析出 0 分 —— 这才是真正的体验灾难。
     */
    if (result.quality.level === 'REJECT') {
      extractQuality.value = result.quality
      extractedFrom.value = result.filename
      ElMessage.error('这个文件读不出内容，请改用粘贴')
      return false
    }

    // ───── 成功：填入编辑框 ─────
    form.content = result.text
    extractQuality.value = result.quality
    extractedFrom.value = result.filename

    /*
     * ★ 自动填标题：用文件名去掉扩展名。
     *
     *   为什么？
     *     用户上传了 "张三-Java后端.pdf"，标题十有八九就是它。
     *     自动填上省一次输入。用户可以改。
     *
     * ★ 但如果文件名是 "resume.pdf" / "简历(1).pdf" 这种没信息量的，
     *   就不要自动填 —— 填一个 "resume" 反而要用户先删掉。
     */
    if (!form.title.trim()) {
      const base = result.filename.replace(/\.[^.]+$/, '').trim()
      if (base && !/^(resume|cv|简历|我的简历|新建)/i.test(base)) {
        form.title = base.slice(0, 64)
      }
    }

    if (result.quality.level === 'WARN') {
      ElMessage.warning('已提取内容，请核对下方文本是否完整')
    } else {
      ElMessage.success(`已提取 ${result.charCount} 字，请核对后创建`)
    }
  } catch (e) {
    // ★ 后端的 BizException 会被 http.js 拦截器弹提示，这里不用重复弹
    extractQuality.value = null
  } finally {
    uploading.value = false
  }

  /*
   * ★ 返回 false 阻止 el-upload 自己上传。
   *   我们用 auto-upload=false + 手动调接口，因为要拿到返回的文本。
   */
  return false
}

async function submit() {
  if (!form.title.trim()) {
    return ElMessage.warning('请填写标题')
  }
  if (!enoughChars.value) {
    return ElMessage.warning(`内容至少 ${MIN_CHARS} 字（当前 ${charCount.value} 字）`)
  }

  submitting.value = true
  try {
    const data = await store.create({
      title: form.title.trim(),
      targetPosition: form.targetPosition.trim() || null,
      content: form.content
    })


    dialogVisible.value = false
    page.value = 1
    await loadList()
    openWorkspace(data)
  } finally {
    submitting.value = false
  }
}

function openWorkspace(row) {
  workspaceResume.value = { id: row.id, title: row.title }
  workspaceVisible.value = true
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `「${row.title}」的 ${row.versionCount} 个版本和所有分析报告都会被删除。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  const wasLastOnPage = store.list.length === 1
  await store.remove(row.id)
  if (wasLastOnPage && page.value > 1) page.value -= 1
  ElMessage.success('已删除')
  await loadList()
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【分数语义色】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 和后端 Grade 枚举一致：
 *      ≥85 EXCELLENT 绿  /  ≥70 GOOD 蓝  /  ≥55 FAIR 橙  /  <55 WEAK 红
 *
 * ★ 为什么要带颜色？
 *   用户扫一眼就能判断"哪几份需要改"。
 *   纯数字看不出好坏（61 分算高还是低？）。
 */
function scoreColor(score) {
  if (score == null) return 'var(--text-faint)'
  if (score >= 85) return 'var(--success)'
  if (score >= 70) return 'var(--info)'
  if (score >= 55) return 'var(--warning)'
  return 'var(--danger)'
}

/** 相对时间，让列表更易读 */
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
</script>

<template>
  <DefaultLayout>
    <!-- ═══════════ 页头 ═══════════ -->
    <div class="page-head">
      <div>
        <h1 class="page-title">简历</h1>
        <p class="page-sub">按岗位归档，每个版本都能回看</p>
      </div>

      <div class="head-actions">
        <!--
          ★ 搜索框放在页头右侧，和"新建"并排。
            用 el-input 的 prefix-icon 放放大镜，符合直觉。
        -->
        <el-input
          v-model="keyword"
          placeholder="搜索标题或岗位"
          clearable
          class="search"
          @input="onSearchInput"
          @clear="clearSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-button type="primary" @click="openCreate">新建简历</el-button>
      </div>
    </div>

    <!-- ═══════════ 加载中 ═══════════ -->
    <div v-if="store.loading" class="loading">
      <el-skeleton :rows="4" animated />
    </div>

    <!-- ═══════════ 空状态 ═══════════ -->
    <div v-else-if="store.list.length === 0" class="empty">
      <div class="empty-icon">简</div>
      <p class="empty-title">{{ keyword ? '没有匹配的简历' : '还没有简历' }}</p>
      <p class="empty-desc">
        {{ keyword ? `没有标题或岗位包含「${keyword}」的简历` : '粘贴一份简历，开始第一次分析' }}
      </p>
      <el-button v-if="keyword" @click="clearSearch">清除搜索</el-button>
      <el-button v-else type="primary" @click="openCreate">新建简历</el-button>
    </div>

    <!-- ═══════════ 卡片列表 ═══════════ -->
    <div v-else class="list">
      <div
        v-for="r in store.list"
        :key="r.id"
        class="card"
        role="button"
        tabindex="0"
        :aria-label="`打开简历：${r.title}`"
        @click="openWorkspace(r)"
        @keydown.enter.self="openWorkspace(r)"
        @keydown.space.self.prevent="openWorkspace(r)"
      >
        <!-- 顶行：标题 + 分数 -->
        <div class="card-top">
          <div class="card-title-wrap">
            <h3 class="card-title">{{ r.title }}</h3>

            <!-- 目标岗位标签 -->
            <span v-if="r.targetPosition" class="tag">
              {{ r.targetPosition }}
            </span>
            <span v-else class="tag muted">未设岗位</span>
          </div>

          <!--
            ★ 分数：有就大号显示（带语义色），没有显示 "—"
              这是卡片上最抢眼的元素，用户扫一眼就能比较
          -->
          <div class="score" :style="{ color: scoreColor(r.latestScore) }">
            <span class="score-num">
              {{ r.latestScore != null ? r.latestScore.toFixed(1) : '—' }}
            </span>
            <span class="score-label">
              {{ r.latestScore != null ? '最近匹配' : '未分析' }}
            </span>
          </div>
        </div>

        <!-- 预览：简历正文前 80 字 -->
        <p v-if="r.currentVersionPreview" class="card-preview">
          {{ r.currentVersionPreview }}
        </p>

        <div v-if="r.latestScore != null" class="analysis-context">
          <span class="analysis-context-label">最近分析</span>
          <span class="analysis-context-target">
            {{ r.latestJdCompany ? `${r.latestJdCompany} · ` : '' }}{{ r.latestJdTitle || '岗位信息已删除' }}
          </span>
          <span class="analysis-context-time">{{ relativeTime(r.latestAnalyzedAt) }}</span>
        </div>

        <!-- 底行：元信息 + 操作 -->
        <div class="card-bottom">
          <div class="meta">
            <span class="meta-item">v{{ r.versionCount }}</span>
            <span class="dot">·</span>
            <span class="meta-item">{{ relativeTime(r.updatedAt) }}</span>
            <template v-if="r.analyzedJdCount">
              <span class="dot">·</span>
              <span class="meta-item">{{ r.analyzedJdCount }} 个岗位记录</span>
            </template>
          </div>

          <div class="card-actions">
            <el-button text size="small" @click.stop="router.push(`/resumes/${r.id}`)">
              详情
            </el-button>
            <el-button text size="small" class="del" @click.stop="remove(r)">
              删除
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <el-pagination
      v-if="store.total > size"
      class="pager"
      background
      layout="total, sizes, prev, pager, next, jumper"
      :page-sizes="[10, 20, 30]"
      :total="store.total"
      :page-size="size"
      :current-page="page"
      @size-change="onSizeChange"
      @current-change="onPage"
    />

    <!-- ═══════════ 新建弹窗 ═══════════ -->
    <el-dialog
      v-model="dialogVisible"
      title="新建简历"
      width="740px"
      top="4vh"
      class="resume-create-dialog"
    >
      <el-form label-position="top">
        <!--
          ═══════════ 上传区 ═══════════
          ★ 放在最上面：用户的第一个动作通常是"我有简历文件"，
            而不是"我要从头写"。把上传放前面符合真实动线。
        -->
        <el-form-item>
          <template #label>
            <span>
              上传简历文件
              <span class="hint">支持 PDF / DOCX，会自动提取文字</span>
            </span>
          </template>

          <el-upload
            drag
            :auto-upload="false"
            :show-file-list="false"
            :on-change="handleFileChange"
            accept=".pdf,.docx"
            class="uploader"
          >
            <div v-if="uploading" class="upload-inner">
              <el-icon class="spin"><Loading /></el-icon>
              <p class="upload-text">正在提取文字…</p>
            </div>

            <div v-else class="upload-inner">
              <div class="upload-icon">↑</div>
              <p class="upload-text">
                拖拽文件到这里，或<span class="link">点击选择</span>
              </p>
              <p class="upload-sub">PDF / DOCX · 不超过 5MB</p>
            </div>
          </el-upload>

          <!--
            ★ 质量提示条：
              WARN  → 黄色，说明"能用但要核对"
              REJECT → 红色，说明"读不出来，请改用粘贴"
          -->
          <el-alert
            v-if="extractQuality && extractQuality.level !== 'PASS'"
            :type="extractQuality.level === 'REJECT' ? 'error' : 'warning'"
            :closable="false"
            show-icon
            class="quality-alert"
          >
            <template #title>
              {{ extractedFrom }} · {{ extractQuality.message }}
            </template>
            <ul v-if="extractQuality.issues?.length" class="issues">
              <li v-for="(i, n) in extractQuality.issues" :key="n">{{ i }}</li>
            </ul>
          </el-alert>

          <!-- 提取成功时的简短说明 -->
          <p v-else-if="extractQuality && extractedFrom" class="ok-note">
            已从 {{ extractedFrom }} 提取 {{ charCount }} 字，可直接编辑
          </p>
        </el-form-item>

        <!-- ═══════════ 分隔线 ═══════════ -->
        <div class="divider"><span>或者直接粘贴</span></div>

        <el-form-item label="标题">
          <el-input
            v-model="form.title"
            placeholder="如：Java 后端-社招版"
            maxlength="64"
          />
        </el-form-item>

        <el-form-item>
          <template #label>
            <span>
              目标岗位
              <span class="hint">用于在列表里区分不同用途的简历</span>
            </span>
          </template>

          <el-autocomplete
            v-model="form.targetPosition"
            :fetch-suggestions="(q, cb) => cb(positionSuggestions
              .filter(s => !q || s.includes(q))
              .map(v => ({ value: v })))"
            placeholder="如：Java 后端开发工程师"
            clearable
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item>
          <template #label>
            <div class="label-row">
              <span>简历正文</span>
              <span class="counter" :class="{ ok: enoughChars }">
                {{ charCount }} 字<template v-if="!enoughChars"> / {{ MIN_CHARS }}</template>
              </span>
            </div>
          </template>

          <el-input
            v-model="form.content"
            type="textarea"
            :rows="14"
            placeholder="上传文件后会自动填入，也可以直接粘贴文本"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          创建
        </el-button>
      </template>
    </el-dialog>

    <ResumeWorkspaceDialog
      v-if="workspaceResume"
      v-model="workspaceVisible"
      :resume-id="workspaceResume.id"
      :resume-title="workspaceResume.title"
      @changed="loadList"
    />

  </DefaultLayout>
</template>

<style scoped>
.pager {
  display: flex;
  justify-content: center;
  margin-top: 22px;
}

/* ═══════════════ 页头 ═══════════════ */

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 28px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.search {
  width: 200px;
}

.search :deep(.el-input__wrapper) {
  border-radius: 8px;
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

/* ═══════════════ 加载/空态 ═══════════════ */

.loading {
  padding: 20px 0;
}

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
  box-shadow: 0 6px 20px rgba(31, 75, 62, 0.16);

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

/* ═══════════════ 卡片列表 ═══════════════ */

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/*
 * ★ 卡片的三段式结构：
 *      顶部：标题 + 岗位标签 + 分数
 *      中部：正文预览
 *      底部：版本号 + 时间 + 删除
 *
 *   这个结构和 ResumeIQ 的报告卡片一致 ——
 *   信息密度高但不拥挤。
 */
.card {
  background: var(--surface);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  padding: 16px 18px;

  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.15s;
}

.card:focus-visible {
  outline: 3px solid rgba(31, 75, 62, 0.55);
  outline-offset: 3px;
}

/*
 * ★ hover 三件事同时发生：
 *      边框变青（品牌色暗示"可点"）
 *      阴影出现（浮起来）
 *      上移 1px（微妙的动效反馈）
 *   三个都很轻，合起来才有"活"的感觉。
 */
.card:hover {
  border-color: rgba(31, 75, 62, 0.24);
  box-shadow: 0 4px 16px rgba(18, 23, 33, 0.07);
  transform: translateY(-1px);
}

/* ─────────── 卡顶 ─────────── */

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.card-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  margin: 0;

  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 岗位标签：浅青底 + 深青字，最克制但能区分 */
.tag {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 500;
  color: var(--brand-deep);
  background: var(--brand-soft);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
}

.tag.muted {
  color: var(--text-faint);
  background: var(--fill);
}

/* ─────────── 分数 ─────────── */

.score {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex-shrink: 0;
  line-height: 1.1;
}

.score-num {
  font-size: 22px;
  font-weight: 700;

  /*
   * ★ 等宽数字：让 "68" 和 "100" 的宽度一致，
   *   多个卡片并排时数字能对齐。
   */
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.score-label {
  font-size: 10px;
  font-weight: 500;
  color: var(--text-faint);
  margin-top: 2px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

/* ─────────── 最近岗位分析 ─────────── */
.analysis-context {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 10px;
  min-width: 0;
  color: var(--text-muted);
  font-size: 11.5px;
}

.analysis-context-label {
  flex: 0 0 auto;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-weight: 600;
}

.analysis-context-target {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.analysis-context-time {
  flex: 0 0 auto;
  margin-left: auto;
  color: var(--text-faint);
}

/* ─────────── 预览 ─────────── */

.card-preview {
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
  margin: 10px 0 0;

  /*
   * ★ 用 line-clamp 限制两行，超出显示省略号。
   *   比单行省略保留更多信息，又不会把卡片撑太高。
   */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ─────────── 卡底 ─────────── */

.card-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-faint);
}

.dot {
  color: var(--line);
}

/*
 * ★ 删除按钮默认隐藏，hover 卡片才出现。
 *   这样列表默认干净，不会满屏"删除"字样。
 */
.card-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.del {
  opacity: 0;
  transition: opacity 0.15s;
  color: var(--danger);
  font-size: 12px;
}

.card:hover .del {
  opacity: 1;
}

/* ═══════════════ 弹窗 ═══════════════ */

/*
 * el-dialog 会 Teleport 到 body，因此这里使用 :global 命中弹窗根节点。
 * 对话框限制在视口内，正文区域独立滚动，底部操作区始终可见。
 */
:global(.resume-create-dialog) {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 8vh);
  max-height: calc(100dvh - 8vh);
  margin-bottom: 0;
  overflow: hidden;
}

:global(.resume-create-dialog .el-dialog__header) {
  flex: 0 0 auto;
}

:global(.resume-create-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

:global(.resume-create-dialog .el-dialog__footer) {
  flex: 0 0 auto;
  border-top: 1px solid var(--line-soft);
  background: var(--surface);
}

.hint {
  font-size: 12px;
  color: var(--text-faint);
  font-weight: normal;
  margin-left: 6px;
}

.label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.counter {
  font-size: 12px;
  color: var(--danger);
  font-weight: normal;
}

.counter.ok {
  color: var(--success);
}

/* ─────────── 上传区 ─────────── */

/*
 * ★ 覆盖 el-upload 的默认样式，让它看起来像个"投放区"而不是按钮。
 *   默认的 drag 区域边框是灰色虚线，这里改成品牌色、加大圆角。
 */
.uploader :deep(.el-upload-dragger) {
  border: 1.5px dashed var(--line);
  border-radius: var(--radius);
  background: var(--surface-deep);
  padding: 20px;
  transition: border-color 0.15s, background 0.15s;
}

.uploader :deep(.el-upload-dragger:hover) {
  border-color: var(--brand);
  background: var(--brand-soft);
}

.upload-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

/*
 * ★ 用纯 CSS 画一个上传图标（箭头 + 不会动），
 *   不引外部图标资源 —— 和整体的极简风格一致。
 */
.upload-icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 17px;
  font-weight: 700;
  line-height: 32px;
  margin-bottom: 4px;
}

.upload-text {
  font-size: 13px;
  color: var(--text);
  margin: 0;
}

.upload-sub {
  font-size: 11px;
  color: var(--text-faint);
  margin: 0;
}

.link {
  color: var(--brand);
  font-weight: 500;
}

/* 提取中：图标旋转 */
.spin {
  animation: rot 1s linear infinite;
  font-size: 20px;
  color: var(--brand);
}

@keyframes rot {
  to { transform: rotate(360deg); }
}

/* ─────────── 质量提示 ─────────── */

.quality-alert {
  margin-top: 10px;
}

.issues {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.7;
}

.ok-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--success);
}

/* ─────────── 分隔线 ─────────── */

/*
 * ★ "或者直接粘贴"的分隔线：
 *   左右两条线 + 中间文字，明确告诉用户"两条路都行"。
 */
.divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 4px 0 16px;
  font-size: 11px;
  color: var(--text-faint);
}

.divider::before,
.divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--line-soft);
}

@media (max-width: 800px) {
  :global(.resume-create-dialog) {
    width: calc(100vw - 24px) !important;
    max-height: calc(100vh - 24px);
    max-height: calc(100dvh - 24px);
    margin-top: 12px !important;
  }

  :global(.resume-create-dialog .el-dialog__body) {
    padding: 8px 14px 12px;
  }

  :global(.resume-create-dialog .el-dialog__footer) {
    padding: 10px 14px 12px;
  }

  .uploader :deep(.el-upload-dragger) {
    padding: 14px 10px;
  }

  .hint {
    display: block;
    margin: 2px 0 0;
  }
}

/* ═══════════════ 方案 C：职业工作室覆盖层 ═══════════════ */
.page-head {
  align-items: flex-end;
  gap: 28px;
  margin-bottom: 30px;
}

.page-title {
  font-family: var(--font-serif);
  font-size: clamp(36px, 4vw, 48px);
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.04em;
  margin-bottom: 9px;
}

.page-title::before {
  content: 'RESUME ARCHIVE';
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 12px;
  color: var(--brand);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.page-title::after {
  content: '';
  display: none;
}

.page-sub {
  color: var(--text-muted);
  font-size: 14px;
}

.head-actions {
  padding: 10px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  background: rgba(255, 252, 247, 0.8);
}

.search { width: 230px; }
.search :deep(.el-input__wrapper) { border-radius: 9px; }

.empty {
  padding: 72px 24px;
  border: 1px dashed var(--line);
  border-radius: var(--radius-xl);
  background: rgba(255, 252, 247, 0.58);
}

.empty-icon {
  border-radius: 16px 16px 16px 4px;
  background: var(--brand);
  box-shadow: 8px 8px 0 rgba(31, 75, 62, 0.08);
}

.empty-title {
  font-family: var(--font-serif);
  font-size: 22px;
  font-weight: 700;
}

.empty-desc {
  max-width: 380px;
  margin-inline: auto;
  line-height: 1.7;
}

.list {
  counter-reset: resume-card;
  gap: 12px;
}

.card {
  position: relative;
  padding: 21px 23px 18px 82px;
  border-color: var(--line-soft);
  border-radius: 18px;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.card::before {
  counter-increment: resume-card;
  content: counter(resume-card, decimal-leading-zero);
  position: absolute;
  left: 23px;
  top: 22px;
  color: var(--accent-deep);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.card::after {
  content: '';
  position: absolute;
  left: 55px;
  top: 26px;
  bottom: 26px;
  width: 1px;
  background: var(--line-soft);
}

.card:hover {
  border-color: rgba(31, 75, 62, 0.28);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.card:focus-within {
  outline: 3px solid rgba(238, 106, 75, 0.28);
  outline-offset: 2px;
}

.card-title {
  color: var(--text);
  font-size: 15px;
}

.tag {
  border-radius: 999px;
  padding: 3px 9px;
}

.score-num {
  font-family: var(--font-serif);
  font-size: 28px;
}

.score-label { margin-top: 4px; }

.card-preview {
  max-width: 760px;
  margin-top: 9px;
  font-size: 12.5px;
}

.card-bottom {
  margin-top: 15px;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}

.card-actions :deep(.el-button) {
  min-height: 36px;
  padding-inline: 9px;
}

.del { opacity: 1; }

.uploader :deep(.el-upload-dragger) {
  background: var(--surface-deep);
}

.uploader :deep(.el-upload-dragger:hover) {
  border-color: var(--brand);
  background: var(--brand-soft);
}

@media (max-width: 760px) {
  .page-head {
    align-items: stretch;
    flex-direction: column;
    gap: 18px;
    margin-bottom: 22px;
  }

  .page-title { font-size: 36px; }

  .head-actions {
    width: 100%;
    flex-wrap: wrap;
  }

  .search {
    width: 100%;
    flex: 1 1 180px;
  }

  .head-actions :deep(.el-button) {
    flex: 1 1 120px;
  }

  .card {
    padding: 18px 17px 16px 60px;
  }

  .card::before { left: 16px; top: 20px; }
  .card::after { left: 45px; top: 24px; bottom: 24px; }
  .card-top { gap: 10px; }
  .card-title { white-space: normal; }
  .score-num { font-size: 24px; }
  .card-actions :deep(.el-button) { min-height: 44px; }
  .del { opacity: 1; }
}
</style>
