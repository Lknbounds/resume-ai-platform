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
import { jdApi } from '@/api/jd'

const list = ref([])
const loading = ref(true)

/**
 * 搜索关键词。防抖 300ms，理由同简历列表。
 */
const keyword = ref('')
let searchTimer = null

function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => load(), 300)
}

function clearSearch() {
  keyword.value = ''
  clearTimeout(searchTimer)
  load()
}

const dialogVisible = ref(false)
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
    const kw = keyword.value.trim()
    list.value = await jdApi.list(kw ? { keyword: kw } : {})
  } finally {
    loading.value = false
  }
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

  await jdApi.remove(jd.id)
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
      <el-button v-if="keyword" @click="clearSearch">清除搜索</el-button>
      <el-button v-else type="primary" @click="openCreate">粘贴 JD</el-button>
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
  </DefaultLayout>
</template>

<style scoped>
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
  box-shadow: 0 6px 20px rgba(41, 163, 153, 0.25);
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
  border-color: rgba(41, 163, 153, 0.25);
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
</style>
