<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  JD 管理。列表 + 新建/编辑 + 显示解析出的技能。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  ★ 和其他页面统一：卡片列表 + 页头 + 弹窗。
 *  ★ 展示重点：解析出了几项技能、要求几年、什么学历 ——
 *    让用户确认"系统真的读懂了这份 JD"。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import JdImportDialog from '@/components/JdImportDialog.vue'
import { jdApi } from '@/api/jd'

const list = ref([])
const loading = ref(true)
const page = ref(1)
const size = ref(10)
const total = ref(0)

/**
 * 搜索关键词。防抖 300ms，理由同简历列表。
 */
const keyword = ref('')
let searchTimer = null

function onSearchInput() {
  clearTimeout(searchTimer)
  page.value = 1
  searchTimer = setTimeout(() => load(), 300)
}

function clearSearch() {
  keyword.value = ''
  clearTimeout(searchTimer)
  page.value = 1
  load()
}

const dialogVisible = ref(false)
const importDialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = reactive({
  title: '',
  company: '',
  content: ''
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    const kw = keyword.value.trim()
    if (kw) params.keyword = kw
    const data = await jdApi.list(params)
    list.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function onPage(nextPage) {
  page.value = nextPage
  load()
}

function onSizeChange(nextSize) {
  size.value = nextSize
  page.value = 1
  load()
}

function onImported() {
  page.value = 1
  load()
}

function openImport() {
  importDialogVisible.value = true
}

function openCreate() {
  editingId.value = null
  form.title = ''
  form.company = ''
  form.content = ''
  dialogVisible.value = true
}

function openEdit(jd) {
  editingId.value = jd.id
  form.title = jd.title
  form.company = jd.company || ''
  form.content = jd.content
  dialogVisible.value = true
}

async function save() {
  if (!form.title.trim()) return ElMessage.warning('请填写岗位名称')
  if (form.content.replace(/\s/g, '').length < 30) {
    return ElMessage.warning('JD 内容至少 30 字')
  }

  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      company: form.company.trim() || null,
      content: form.content
    }

    if (editingId.value) {
      await jdApi.update(editingId.value, payload)
      ElMessage.success('已更新并重新解析')
    } else {
      await jdApi.create(payload)
      ElMessage.success('已保存并解析')
    }

    dialogVisible.value = false
    page.value = 1
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(jd) {
  try {
    await ElMessageBox.confirm(
      `删除「${jd.title}」会同时删除用它做过的分析报告。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  const wasLastOnPage = list.value.length === 1
  await jdApi.remove(jd.id)
  if (wasLastOnPage && page.value > 1) page.value -= 1
  ElMessage.success('已删除')
  await load()
}

/** 重要度 → 标签颜色 */
function importanceType(imp) {
  return { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }[imp] || 'info'
}

/** 学历英文 → 中文 */
function degreeName(d) {
  return {
    PHD: '博士', MASTER: '硕士', BACHELOR: '本科',
    ASSOCIATE: '大专', NONE: '不限'
  }[d] || d
}
</script>

<template>
  <DefaultLayout>
    <!-- ═══════════ 页头 ═══════════ -->
    <div class="page-head">
      <div>
        <h1 class="page-title">岗位 JD</h1>
        <p class="page-sub">保存目标岗位，分析时用来对比技能要求</p>
      </div>

      <div class="head-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索岗位或公司"
          clearable
          class="search"
          @input="onSearchInput"
          @clear="clearSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-button class="import-button" @click="openImport">
          <el-icon><UploadFilled /></el-icon>
          导入文件
        </el-button>
        <el-button type="primary" @click="openCreate">新建 JD</el-button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />

    <!-- ═══════════ 空状态 ═══════════ -->
    <div v-else-if="list.length === 0" class="empty">
      <div class="empty-icon">简</div>
      <p class="empty-title">{{ keyword ? '没有匹配的 JD' : '还没有保存的 JD' }}</p>
      <p class="empty-desc">
        {{ keyword ? `没有岗位或公司包含「${keyword}」` : '粘贴一份岗位描述，系统会自动抽出技能要求' }}
      </p>
      <template v-if="keyword">
        <el-button @click="clearSearch">清除搜索</el-button>
      </template>
      <template v-else>
        <el-button type="primary" @click="openCreate">粘贴 JD</el-button>
        <el-button @click="openImport">导入文件</el-button>
      </template>
    </div>

    <!-- ═══════════ 列表 ═══════════ -->
    <div v-else class="list">
      <div v-for="jd in list" :key="jd.id" class="card">
        <!-- 顶行 -->
        <div class="card-top">
          <div class="card-title-wrap">
            <h3 class="card-title">{{ jd.title }}</h3>
            <span v-if="jd.company" class="company">{{ jd.company }}</span>
          </div>

          <div class="actions">
            <el-button text size="small" @click="openEdit(jd)">编辑</el-button>
            <el-button text size="small" class="del" @click="remove(jd)">删除</el-button>
          </div>
        </div>

        <!-- 要求摘要 -->
        <div v-if="jd.parsed && jd.parsed.requiredSkills?.length" class="reqs">
          <div class="req-line">
            <span class="req-label">硬技能</span>
            <div class="req-tags">
              <el-tag
                v-for="s in jd.parsed.requiredSkills"
                :key="s.name"
                size="small"
                :type="importanceType(s.importance)"
                effect="plain"
              >
                {{ s.name }}
              </el-tag>
            </div>
          </div>

          <div v-if="jd.parsed.niceToHaveSkills?.length" class="req-line">
            <span class="req-label">加分项</span>
            <div class="req-tags">
              <el-tag
                v-for="s in jd.parsed.niceToHaveSkills"
                :key="s.name"
                size="small"
                type="info"
                effect="plain"
              >
                {{ s.name }}
              </el-tag>
            </div>
          </div>
        </div>

        <!-- 底行 -->
        <div class="card-bottom">
          <span class="meta-item">
            {{ jd.parsed?.minYearsExperience || 0 }} 年经验
          </span>
          <span class="dot">·</span>
          <span class="meta-item">{{ degreeName(jd.parsed?.educationRequirement) }}</span>
          <span class="dot">·</span>
          <span class="meta-item">{{ jd.parsed?.requiredSkills?.length || 0 }} 项技能</span>
          <span class="dot">·</span>
          <span class="meta-item">{{ jd.parsedSource === 'AI' ? 'AI 解析' : '规则解析' }}</span>
        </div>
      </div>
    </div>

    <el-pagination
      v-if="total > size"
      class="pager"
      background
      layout="total, sizes, prev, pager, next, jumper"
      :page-sizes="[10, 20, 30]"
      :total="total"
      :page-size="size"
      :current-page="page"
      @size-change="onSizeChange"
      @current-change="onPage"
    />

    <!-- ═══════════ 弹窗 ═══════════ -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑 JD' : '新建 JD'"
      width="680px"
      top="8vh"
    >
      <el-form label-position="top">
        <div class="row2">
          <el-form-item label="岗位名称">
            <el-input v-model="form.title" placeholder="如：Java 后端开发工程师" maxlength="100" />
          </el-form-item>
          <el-form-item label="公司（选填）">
            <el-input v-model="form.company" placeholder="如：某科技" maxlength="100" />
          </el-form-item>
        </div>

        <el-form-item label="JD 内容">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="16"
            placeholder="把岗位描述粘贴到这里&#10;&#10;含「任职要求」的 JD 解析效果最好"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ editingId ? '保存并重新解析' : '保存并解析' }}
        </el-button>
      </template>
    </el-dialog>

    <JdImportDialog
      v-model:visible="importDialogVisible"
      @imported="onImported"
    />
  </DefaultLayout>
</template>

<style scoped>
.pager {
  display: flex;
  justify-content: center;
  margin-top: 22px;
}

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

.import-button {
  color: var(--brand);
  border-color: rgba(31, 75, 62, 0.2);
  background: rgba(255, 252, 247, 0.65);
}

.import-button:hover {
  color: var(--brand-deep);
  border-color: rgba(31, 75, 62, 0.36);
  background: var(--brand-soft);
}

.import-button .el-icon {
  margin-right: 5px;
}

.search {
  width: 200px;
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

/* 空状态 */
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

/* 列表 */
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
  transition: border-color 0.15s, box-shadow 0.15s;
}

.card:hover {
  border-color: rgba(31, 75, 62, 0.16);
  box-shadow: 0 2px 10px rgba(18, 23, 33, 0.05);
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
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
}

.company {
  font-size: 11px;
  color: var(--text-faint);
  background: var(--fill);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
}

.actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}

.del {
  color: var(--danger);
}

/* 技能要求 */
.reqs {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.req-line {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.req-label {
  flex-shrink: 0;
  width: 44px;
  font-size: 11px;
  color: var(--text-faint);
  padding-top: 3px;
}

.req-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

/* 底部元信息 */
.card-bottom {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 11px;
  color: var(--text-faint);
}

.dot {
  color: var(--line);
}

.meta-item {
  white-space: nowrap;
}

/* 弹窗 */
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

@media (max-width: 760px) {
  .row2 {
    grid-template-columns: 1fr;
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
  content: 'ROLE NOTES';
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

.page-sub { font-size: 14px; }

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
  max-width: 420px;
  margin-inline: auto;
  line-height: 1.7;
}

.list {
  counter-reset: jd-card;
  gap: 12px;
}

.card {
  position: relative;
  padding: 20px 22px 18px 76px;
  border-radius: 18px;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.card::before {
  counter-increment: jd-card;
  content: 'JD ' counter(jd-card, decimal-leading-zero);
  position: absolute;
  left: 18px;
  top: 21px;
  color: var(--accent-deep);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.07em;
}

.card::after {
  content: '';
  position: absolute;
  left: 61px;
  top: 24px;
  bottom: 24px;
  width: 1px;
  background: var(--line-soft);
}

.card:hover {
  border-color: rgba(31, 75, 62, 0.28);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.card { transition: border-color .18s, box-shadow .18s, transform .18s; }
.card-title { font-size: 15px; }
.company { border-radius: 999px; }
.req-label { color: var(--text-muted); }
.actions :deep(.el-button) { min-height: 36px; }

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

  .head-actions :deep(.el-button) { flex: 1 1 120px; }

  .card { padding: 18px 16px 16px 58px; }
  .card::after { left: 45px; }
  .card-top { flex-wrap: wrap; }
  .actions { margin-left: auto; }
  .actions :deep(.el-button) { min-height: 44px; }
  .card-bottom { flex-wrap: wrap; line-height: 1.7; }
}
</style>
