<script setup>
import { ref, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { jdApi } from '@/api/jd'

const props = defineProps({
  visible: Boolean
})

const emit = defineEmits(['update:visible', 'imported'])

const uploadRef = ref()
const selectedFile = ref(null)
const importing = ref(false)
const result = ref(null)
const errorMessage = ref('')

const MAX_FILE_SIZE = 2 * 1024 * 1024

/**
 * 页面里直接展示格式示例。
 * 用数组拼接而不是模板字符串，避免源码里的反引号影响阅读。
 */
const formatExample = [
  '## 1. Java 后端开发工程师',
  '- 公司：某科技有限公司',
  '- 技能标签：Java / MySQL / Redis',
  '',
  '**岗位描述 / 任职资格**',
  '',
  '```text',
  '岗位职责',
  '1. 负责核心业务系统的后端开发',
  '',
  '任职要求',
  '1. 熟悉 Java、Spring Boot、MySQL',
  '```',
  '',
  '## 2. 前端开发工程师',
  '- 公司：另一家科技有限公司',
  '- 技能标签：Vue / JavaScript',
  '',
  '岗位职责与任职要求……'
].join('\n')

const statusMeta = {
  IMPORTED: { label: '已导入', tagType: 'success' },
  DUPLICATE: { label: '已跳过', tagType: 'warning' },
  FAILED: { label: '导入失败', tagType: 'danger' }
}

watch(() => props.visible, (visible) => {
  if (!visible) return

  selectedFile.value = null
  result.value = null
  errorMessage.value = ''
  importing.value = false

  nextTick(() => uploadRef.value?.clearFiles())
})

function onFileChange(uploadFile) {
  errorMessage.value = ''
  result.value = null

  const file = uploadFile?.raw
  if (!file) return

  const lowerName = (file.name || '').toLowerCase()
  if (!lowerName.endsWith('.md') && !lowerName.endsWith('.txt')) {
    errorMessage.value = '仅支持 .md 或 .txt 文件，请重新选择。'
    selectedFile.value = null
    nextTick(() => uploadRef.value?.clearFiles())
    return
  }

  if (file.size > MAX_FILE_SIZE) {
    errorMessage.value = '文件不能超过 2MB，请压缩内容后重新上传。'
    selectedFile.value = null
    nextTick(() => uploadRef.value?.clearFiles())
    return
  }

  selectedFile.value = file
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function clearFile() {
  selectedFile.value = null
  uploadRef.value?.clearFiles()
}

function downloadExample() {
  const blob = new Blob([formatExample], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '岗位JD导入示例.md'
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

function statusOf(item) {
  return statusMeta[item.status] || { label: item.status, tagType: 'info' }
}

async function importFile() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择一个 .md 或 .txt 文件')
    return
  }

  importing.value = true
  errorMessage.value = ''
  result.value = null

  try {
    result.value = await jdApi.importFile(selectedFile.value)

    if (result.value.success > 0) {
      ElMessage.success(`成功导入 ${result.value.success} 个岗位`)
      emit('imported', result.value)
    } else if (result.value.duplicate > 0) {
      ElMessage.info('文件中的岗位已经导入过，没有新增记录')
    } else {
      ElMessage.warning('没有新增岗位，请查看下方失败原因')
    }
  } catch (e) {
    errorMessage.value = e?.message || '导入失败，请检查文件格式后重试。'
  } finally {
    importing.value = false
  }
}

function close() {
  if (importing.value) return
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="批量导入岗位 JD"
    width="780px"
    top="6vh"
    class="jd-import-dialog"
    :close-on-click-modal="!importing"
    :close-on-press-escape="!importing"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="import-intro">
      <div class="intro-mark"><el-icon><UploadFilled /></el-icon></div>
      <div>
        <h2>把整理好的 JD 一次导入</h2>
        <p>一个文件可以放多份岗位，每份岗位都会自动解析技能、年限和学历要求。</p>
      </div>
    </div>

    <div class="format-notice">
      <el-icon class="notice-icon"><WarningFilled /></el-icon>
      <div>
        <strong>每个岗位都必须有标题</strong>
        <span>标题统一写成 <code>## 岗位名称</code>，例如 <code>## 1. Java 后端开发工程师</code>。</span>
      </div>
    </div>

    <div class="import-grid">
      <section class="upload-panel">
        <div class="panel-label">上传文件</div>
        <el-upload
          ref="uploadRef"
          class="jd-upload"
          drag
          accept=".md,.txt"
          :auto-upload="false"
          :show-file-list="false"
          :disabled="importing"
          :on-change="onFileChange"
          :on-remove="() => (selectedFile = null)"
        >
          <div class="drop-content">
            <div class="drop-icon"><el-icon><Document /></el-icon></div>
            <p>拖拽 .md / .txt 文件到这里</p>
            <span>或 <em>点击选择文件</em></span>
            <small>单个文件不超过 2MB，最多 200 个岗位</small>
          </div>
        </el-upload>

        <div v-if="selectedFile" class="selected-file">
          <div class="file-icon"><el-icon><Document /></el-icon></div>
          <div class="file-meta">
            <strong>{{ selectedFile.name }}</strong>
            <span>{{ formatSize(selectedFile.size) }} · 等待导入</span>
          </div>
          <el-button text class="file-remove" :disabled="importing" @click="clearFile">
            移除
          </el-button>
        </div>

        <p class="upload-tip">
          支持 UTF-8 编码的 Markdown / 纯文本文件。岗位之间可以用 <code>---</code> 分隔。
        </p>
      </section>

      <section class="example-panel">
        <div class="example-head">
          <div>
            <div class="panel-label">格式示例</div>
            <span class="example-hint">照这个结构整理即可</span>
          </div>
          <el-button link type="primary" @click="downloadExample">下载示例文件</el-button>
        </div>
        <pre>{{ formatExample }}</pre>
      </section>
    </div>

    <el-alert
      v-if="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="import-alert"
      :title="errorMessage"
    />

    <section v-if="result" class="result-panel">
      <div class="result-head">
        <div>
          <div class="panel-label">导入结果</div>
          <strong>{{ result.fileName }}</strong>
        </div>
        <span class="result-total">共识别 {{ result.total }} 个岗位</span>
      </div>

      <div class="result-stats">
        <div class="stat stat-success">
          <strong>{{ result.success }}</strong>
          <span>已导入</span>
        </div>
        <div class="stat stat-duplicate">
          <strong>{{ result.duplicate }}</strong>
          <span>重复跳过</span>
        </div>
        <div class="stat stat-failed">
          <strong>{{ result.failed }}</strong>
          <span>失败</span>
        </div>
      </div>

      <div class="result-list">
        <div v-for="item in result.items" :key="item.index" class="result-item">
          <span class="result-index">{{ String(item.index).padStart(2, '0') }}</span>
          <div class="result-copy">
            <div class="result-title-line">
              <strong>{{ item.title }}</strong>
              <span v-if="item.company">{{ item.company }}</span>
            </div>
            <p>{{ item.message }}</p>
          </div>
          <el-tag :type="statusOf(item).tagType" size="small" effect="plain">
            {{ statusOf(item).label }}
          </el-tag>
        </div>
      </div>
    </section>

    <template #footer>
      <span class="footer-tip">导入后会立即刷新岗位列表；重复岗位不会重复创建。</span>
      <el-button :disabled="importing" @click="close">关闭</el-button>
      <el-button
        type="primary"
        :loading="importing"
        :disabled="!selectedFile || importing"
        @click="importFile"
      >
        {{ importing ? '正在解析并导入…' : '开始导入' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-intro {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 16px;
}

.intro-mark {
  display: grid;
  place-items: center;
  flex: 0 0 46px;
  width: 46px;
  height: 46px;
  border-radius: 14px 14px 14px 4px;
  background: var(--brand);
  color: #fff;
  font-size: 22px;
  box-shadow: 6px 6px 0 rgba(31, 75, 62, 0.08);
}

.import-intro h2 {
  margin: 0 0 4px;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 20px;
}

.import-intro p {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.65;
}

.format-notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid rgba(238, 106, 75, 0.24);
  border-radius: 12px;
  background: var(--accent-soft);
  color: var(--text);
  font-size: 12px;
  line-height: 1.7;
}

.notice-icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: var(--accent-deep);
  font-size: 17px;
}

.format-notice strong {
  display: block;
  margin-bottom: 1px;
}

.format-notice span {
  color: var(--text-muted);
}

code {
  padding: 1px 5px;
  border-radius: 5px;
  background: rgba(255, 252, 247, 0.7);
  color: var(--accent-deep);
  font-family: var(--font-mono);
  font-size: 11px;
}

.import-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  gap: 14px;
  margin-top: 18px;
}

.upload-panel,
.example-panel {
  min-width: 0;
  padding: 15px;
  border: 1px solid var(--line-soft);
  border-radius: 16px;
  background: rgba(255, 252, 247, 0.62);
}

.panel-label {
  margin-bottom: 9px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.jd-upload :deep(.el-upload) {
  width: 100%;
}

.jd-upload :deep(.el-upload-dragger) {
  padding: 22px 16px 20px;
  border: 1px dashed var(--line);
  border-radius: 13px;
  background: var(--surface);
  transition: border-color 0.18s, background 0.18s;
}

.jd-upload :deep(.el-upload-dragger:hover) {
  border-color: var(--brand);
  background: var(--brand-soft);
}

.drop-content {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 5px;
}

.drop-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  margin-bottom: 5px;
  border-radius: 11px;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 19px;
}

.drop-content p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
}

.drop-content span {
  color: var(--text-muted);
  font-size: 12px;
}

.drop-content em {
  color: var(--brand);
  font-style: normal;
  font-weight: 600;
}

.drop-content small {
  margin-top: 3px;
  color: var(--text-faint);
  font-size: 10px;
}

.selected-file {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 10px;
  padding: 9px 10px;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  background: var(--surface);
}

.file-icon {
  display: grid;
  place-items: center;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--brand-soft);
  color: var(--brand);
}

.file-meta {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.file-meta strong {
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta span {
  color: var(--text-faint);
  font-size: 10px;
}

.file-remove {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-size: 11px;
}

.upload-tip {
  margin: 10px 2px 0;
  color: var(--text-faint);
  font-size: 10px;
  line-height: 1.6;
}

.example-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 9px;
}

.example-hint {
  display: block;
  margin-top: -5px;
  color: var(--text-faint);
  font-size: 10px;
}

.example-panel pre {
  max-height: 270px;
  margin: 0;
  overflow: auto;
  padding: 13px;
  border: 1px solid var(--line-soft);
  border-radius: 11px;
  background: var(--brand-deep);
  color: #e9f0eb;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.import-alert {
  margin-top: 14px;
}

.result-panel {
  margin-top: 16px;
  padding: 15px;
  border: 1px solid var(--line-soft);
  border-radius: 16px;
  background: var(--surface);
}

.result-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.result-head strong {
  color: var(--text);
  font-size: 13px;
}

.result-total {
  color: var(--text-faint);
  font-size: 11px;
}

.result-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}

.stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--fill);
}

.stat strong {
  font-family: var(--font-num);
  font-size: 21px;
  line-height: 1;
}

.stat span {
  color: var(--text-muted);
  font-size: 10px;
}

.stat-success strong { color: var(--success); }
.stat-duplicate strong { color: var(--warning); }
.stat-failed strong { color: var(--danger); }

.result-list {
  display: flex;
  max-height: 220px;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
}

.result-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 9px;
  border-radius: 9px;
  background: var(--fill);
}

.result-index {
  flex: 0 0 22px;
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 10px;
}

.result-copy {
  min-width: 0;
  flex: 1;
}

.result-title-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.result-title-line strong {
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-title-line span {
  overflow: hidden;
  color: var(--text-faint);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-copy p {
  margin: 1px 0 0;
  color: var(--text-muted);
  font-size: 10px;
}

.footer-tip {
  float: left;
  color: var(--text-faint);
  font-size: 10px;
  line-height: 32px;
}
</style>
