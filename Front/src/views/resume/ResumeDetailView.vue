<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  简历详情。左边是版本时间线，右边是某个版本的正文（可编辑）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：一次打开页面的三次请求】
 *
 *      进入 /resumes/1
 *         ↓
 *      ① fetchDetail(1)  → GET /api/resumes/1
 *            拿到：title、currentVersionId=2、versions=[v2,v1]
 *         ↓
 *      ② fetchVersion(2) → GET /api/resumes/versions/2
 *            拿到：v2 的完整正文
 *         ↓
 *      页面显示：左侧时间线（v2 高亮）+ 右侧正文
 *
 *      用户点 v1
 *         ↓
 *      ③ fetchVersion(1) → GET /api/resumes/versions/1
 *         ↓
 *      右侧换成 v1 的正文
 *
 *  ★ 注意：切换版本时【没有重新拉详情】——
 *    因为版本列表没变，只有正文变了。
 *    这正是后端"详情不含正文"设计的价值：
 *    切换版本只传一份正文，而不是每次传整个详情。
 * ═══════════════════════════════════════════════════════════════════════
 */
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import BackButton from '@/components/BackButton.vue'
import { useResumeStore } from '@/stores/resume'
import { resumeApi } from '@/api/resume'

const route = useRoute()
const router = useRouter()
const store = useResumeStore()

/** 当前选中的版本 id */
const activeVersionId = ref(null)

/** 是否在编辑模式 */
const editing = ref(false)

/** 编辑中的草稿内容 */
const draft = ref('')

/** 变更说明 */
const changeNote = ref('')

/** 保存中 */
const saving = ref(false)

/** 版本正文加载中（局部，不用 store 的 loading） */
const versionLoading = ref(false)

/** 编辑简历信息（标题 + 目标岗位）的弹窗 */
const editInfoVisible = ref(false)
const infoSaving = ref(false)
const infoForm = reactive({ title: '', targetPosition: '' })

/**
 * 当前版本对象（含正文）。
 *
 * ★ ?? 是什么？
 *   空值合并运算符。store.currentVersion 为 null/undefined 时返回 null。
 *   目的是避免模板里写 store.currentVersion?.versionNo 到处判空。
 */
const version = computed(() => store.currentVersion)

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【字数统计】和后端算法一致
 * ═══════════════════════════════════════════════════════════════════════
 */
const MIN_CHARS = 50
const draftCharCount = computed(() => draft.value.replace(/\s/g, '').length)
const enoughChars = computed(() => draftCharCount.value >= MIN_CHARS)

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【是否改动过】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 用途：如果内容没改，"保存为新版本"按钮应该禁用。
 *   否则用户点了会收到"内容与已有版本相同"的错误 ——
 *   那是后端兜底，前端应该提前阻止。
 *
 * ★ 怎么判断"改过"？
 *   比较草稿和当前版本的正文。
 *   完全相等 → 没改。
 *
 *   ★ 注意这里用的是【严格相等】而不是"去掉空白后相等"：
 *     只改了一个空格也算"改了"（虽然后端归一化后 hash 可能相同）。
 *   ★ 更精确的做法是前端也做归一化再比，
 *     但那样用户改了空格却显示"未修改"，也很困惑。
 *
 *   ★ 所以策略是：前端按"原始文本是否相等"判断按钮状态，
 *     如果用户只改了空格，后端会拒绝并提示"内容未变化"——
 *     这是两层保护，不冲突。
 */
const isDirty = computed(() => {
  return !!version.value && draft.value !== version.value.content
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【进入页面】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 为什么用 async 的 onMounted 而不是 onMounted(() => { ... }) 里写 promise？
 *   两种都行。async 写法更直观：读起来像同步代码。
 *   ★ 但要注意：Vue 不会等 onMounted 的 Promise，
 *     所以页面会先渲染再填充数据（这就是为什么需要 loading 状态）。
 */
onMounted(async () => {
  await load()
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【离开页面时清理】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 为什么必须清理？
 *   用户看了简历 A，返回列表，点简历 B。
 *   如果不清，B 的页面在加载完之前会先显示 A 的数据 —— 很怪。
 *
 * ★ 这是"store 保存了跨页面数据"的代价：必须记得清理。
 *   ★ 如果用页面内 ref，就不存在这个坑（组件销毁时自动回收）。
 *   ★ 本项目选 store 是为了"返回列表时保留数据避免闪烁"，
 *     所以用 onUnmounted 清理详情数据是必须的配套动作。
 */
onUnmounted(() => {
  store.clearCurrent()
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【加载：详情 + 当前版本正文】
 * ═══════════════════════════════════════════════════════════════════════
 */
async function load() {
  const detail = await store.fetchDetail(route.params.id)

  /*
   * ★ 默认选中"当前版本"（最新的那个）。
   *   detail.currentVersionId 可能就是 null（历史数据异常时），
   *   所以兜底成 versions[0]?.id。
   */
  activeVersionId.value = detail.currentVersionId || detail.versions?.[0]?.id

  if (activeVersionId.value) {
    await loadVersion(activeVersionId.value)
  }
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【加载某个版本的正文】
 * ═══════════════════════════════════════════════════════════════════════
 */
async function loadVersion(id) {
  versionLoading.value = true
  try {
    const v = await store.fetchVersion(id)

    /*
     * ★ 把正文复制到草稿，然后退出编辑模式。
     *   ★ 为什么不在切换版本时保留编辑中的内容？
     *     因为用户可能改了一半然后点了另一个版本 ——
     *     这时候他显然是想看那一版本，不是想继续编辑。
     *     直接丢弃草稿是符合直觉的（虽然有点粗暴）。
     *
     *   ★ 更好的做法是"切换时检测未保存的修改，弹窗确认"。
     *     本项目简化处理，但在 UI 上把"编辑"做成显式按钮 ——
     *     用户点了编辑才会离开浏览态，减少误操作。
     */
    draft.value = v.content
    editing.value = false
    changeNote.value = ''
  } finally {
    versionLoading.value = false
  }
}

/**
 * ★ 监听 activeVersionId 变化，自动加载对应正文。
 *
 * ★ 为什么用 watch 而不是在点击时直接调 loadVersion？
 *   因为模板里是 v-model / @click 改 activeVersionId，
 *   用 watch 能保证"不管从哪里改了这个值，都会加载"。
 *   ★ 如果要防止"编辑中切换"误触，可以在 watch 里加判断。
 */
watch(activeVersionId, (id, oldId) => {
  /*
   * ★ 为什么判断 oldId !== undefined？
   *   watch 默认在初始化时不会触发（除非 immediate: true）。
   *   但 activeVersionId 从 null 变成具体值时会触发一次 ——
   *   那是 load() 里设置的，此时已经在 load() 里手动调过 loadVersion 了，
   *   这里会重复请求一次。
   *
   *   ★ 所以加个判断：只有"用户主动切换"（oldId 有值）时才加载。
   *     更简洁的做法是在 load() 里不调 loadVersion，全靠 watch ——
   *     但那样初始化时序不好控制。这里选显式 + 判断。
   */
  if (id && oldId) {
    loadVersion(id)
  }
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【进入编辑模式】
 * ═══════════════════════════════════════════════════════════════════════
 */
function startEdit() {
  /*
   * ★ 为什么不直接 editing = true？
   *   因为要保证草稿是最新内容。如果用户先浏览了 v1 再浏览 v2，
   *   草稿应该是 v2 的内容 —— loadVersion 已经处理了。
   *   这里再同步一次是防御性写法（万一有人直接改了 editing）。
   */
  draft.value = version.value?.content || ''
  editing.value = true
}

/**
 * 取消编辑 → 恢复成当前版本的内容。
 */
function cancelEdit() {
  draft.value = version.value?.content || ''
  changeNote.value = ''
  editing.value = false
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【保存为新版本】
 * ═══════════════════════════════════════════════════════════════════════
 */
async function saveNewVersion() {
  if (!enoughChars.value) {
    return ElMessage.warning(`简历内容至少 ${MIN_CHARS} 字（当前 ${draftCharCount.value} 字）`)
  }
  if (!isDirty.value) {
    return ElMessage.warning('内容没有变化')
  }

  saving.value = true
  try {
    /*
     * ★ 调用 addVersion：
     *   后端会做三件事：锁行 → 校验 → 插入新版本并更新指针
     *   store.addVersion 内部还会重新 fetchDetail（刷新版本列表）
     */
    const v = await store.addVersion(store.current.id, {
      content: draft.value,
      changeNote: changeNote.value || null,
      source: 'EDIT'
    })

    ElMessage.success('已保存为 v' + v.versionNo)

    /*
     * ★ 保存后切到新版本，并退出编辑模式。
     *   ★ 注意 store.addVersion 已经把 currentVersion 设成新版本了，
     *     所以这里只需要改 activeVersionId 和 editing。
     */
    activeVersionId.value = v.id
    editing.value = false
    changeNote.value = ''
  } finally {
    saving.value = false
  }
}

/**
 * 格式化来源。
 */
const sourceLabel = {
  PASTE: '初始粘贴',
  EDIT: '手动编辑',
  ADOPT_COMMENT: '采纳建议'
}

const sourceType = {
  PASTE: 'info',
  EDIT: 'primary',
  ADOPT_COMMENT: 'success'
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【编辑简历信息】标题 + 目标岗位
 * ═══════════════════════════════════════════════════════════════════════
 */
function openEditInfo() {
  infoForm.title = store.current.title
  infoForm.targetPosition = store.current.targetPosition || ''
  editInfoVisible.value = true
}

async function saveInfo() {
  if (!infoForm.title.trim()) {
    return ElMessage.warning('标题不能为空')
  }

  infoSaving.value = true
  try {
    // ★ 空字符串转 null，和后端语义一致（都表示"没填"）
    await resumeApi.rename(store.current.id, {
      title: infoForm.title.trim(),
      targetPosition: infoForm.targetPosition.trim() || null
    })

    // 重新拉详情，保证页面数据和数据库一致
    await store.fetchDetail(store.current.id)

    editInfoVisible.value = false
    ElMessage.success('已保存')
  } finally {
    infoSaving.value = false
  }
}
</script>

<template>
  <DefaultLayout>
    <BackButton to="/resumes" label="返回简历列表" />

    <el-skeleton v-if="!store.current" :rows="8" animated />

    <div v-else class="detail-page">
      <header class="studio-page-head">
        <div class="studio-page-copy">
          <p class="studio-eyebrow">RESUME FILE</p>
          <h1 class="studio-page-title">{{ store.current.title }}</h1>
          <div class="title-meta">
            <button
              v-if="store.current.targetPosition"
              type="button"
              class="position-tag"
              @click="openEditInfo"
            >
              {{ store.current.targetPosition }}
            </button>
            <button
              v-else
              type="button"
              class="position-tag muted"
              @click="openEditInfo"
            >
              设置目标岗位
            </button>
            <span class="meta-divider">·</span>
            <span>{{ store.current.versions?.length || 0 }} 个版本</span>
          </div>
        </div>

        <div class="studio-page-actions">
          <el-button @click="openEditInfo">
            <el-icon><EditPen /></el-icon>
            编辑简历信息
          </el-button>
        </div>
      </header>

      <div class="detail-grid">
        <aside class="studio-paper versions-panel">
          <header class="panel-heading">
            <div>
              <p class="panel-kicker">VERSION LOG</p>
              <h2>版本记录</h2>
            </div>
            <span class="panel-count">{{ store.current.versions?.length || 0 }}</span>
          </header>

          <el-timeline class="version-timeline">
            <el-timeline-item
              v-for="v in store.current.versions"
              :key="v.id"
              :timestamp="v.createdAt"
              placement="top"
              :type="v.id === store.current.currentVersionId ? 'primary' : ''"
            >
              <button
                type="button"
                class="ver"
                :class="{ active: v.id === activeVersionId }"
                :aria-pressed="v.id === activeVersionId"
                @click="activeVersionId = v.id"
              >
                <span class="ver-title">
                  v{{ v.versionNo }}
                  <el-tag size="small" :type="sourceType[v.source]" effect="plain">
                    {{ sourceLabel[v.source] || v.source }}
                  </el-tag>
                  <el-tag
                    v-if="v.id === store.current.currentVersionId"
                    size="small"
                    type="primary"
                    effect="dark"
                  >
                    当前
                  </el-tag>
                </span>
                <span class="ver-note">{{ v.changeNote || '暂无变更说明' }}</span>
                <span class="ver-meta">{{ v.charCount }} 字</span>
              </button>
            </el-timeline-item>
          </el-timeline>
        </aside>

        <section class="studio-paper document-panel">
          <header class="document-head">
            <div class="document-title">
              <p class="panel-kicker">DOCUMENT</p>
              <h2>
                v{{ version?.versionNo }}
                <span>{{ version?.charCount }} 字</span>
              </h2>
              <span v-if="editing" class="editing-hint">
                编辑中 {{ draftCharCount }} 字<span v-if="!enoughChars">
                ，至少需 {{ MIN_CHARS }} 字</span>
              </span>
            </div>

            <div class="actions">
              <template v-if="!editing">
                <el-button @click="startEdit">
                  <el-icon><EditPen /></el-icon>
                  编辑内容
                </el-button>
                <el-button type="primary" disabled>发起分析</el-button>
              </template>

              <template v-else>
                <el-input
                  v-model="changeNote"
                  class="change-note"
                  placeholder="变更说明（可选）"
                  maxlength="255"
                />
                <el-button @click="cancelEdit">取消</el-button>
                <el-button
                  type="primary"
                  :loading="saving"
                  :disabled="!enoughChars || !isDirty"
                  @click="saveNewVersion"
                >
                  保存为新版本
                </el-button>
              </template>
            </div>
          </header>

          <el-skeleton v-if="versionLoading" :rows="10" animated />

          <el-input
            v-else-if="editing"
            v-model="draft"
            type="textarea"
            class="document-editor"
            :rows="26"
          />

          <div v-else class="pre-wrap content-view">{{ version?.content }}</div>

          <footer class="document-foot">
            <span>纯文本展示 · 保留原有换行</span>
            <span v-if="isDirty">有未保存的修改</span>
            <span v-else>当前内容已保存</span>
          </footer>
        </section>
      </div>
    </div>

    <el-dialog v-model="editInfoVisible" title="编辑简历信息" width="480px">
      <el-form label-position="top">
        <el-form-item label="标题">
          <el-input v-model="infoForm.title" maxlength="64" />
        </el-form-item>
        <el-form-item label="目标岗位">
          <el-input
            v-model="infoForm.targetPosition"
            placeholder="如：Java 后端开发工程师"
            maxlength="64"
            clearable
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editInfoVisible = false">取消</el-button>
        <el-button type="primary" :loading="infoSaving" @click="saveInfo">
          保存
        </el-button>
      </template>
    </el-dialog>
  </DefaultLayout>
</template>

<style scoped>
.detail-page { padding-bottom: 44px; }

.title-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 9px;
  margin-top: 15px;
  color: var(--text-faint);
  font-size: 12px;
}

.position-tag {
  min-height: 32px;
  padding: 5px 11px;
  border: 1px solid rgba(31, 75, 62, 0.12);
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.position-tag:hover { border-color: var(--brand); }

.position-tag.muted {
  border-style: dashed;
  border-color: var(--line);
  background: transparent;
  color: var(--text-muted);
}

.meta-divider { color: var(--line); }

.detail-grid {
  display: grid;
  grid-template-columns: minmax(230px, 0.72fr) minmax(0, 2.28fr);
  align-items: start;
  gap: 16px;
}

.versions-panel {
  position: sticky;
  top: 94px;
  overflow: hidden;
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 19px 20px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.panel-kicker {
  margin: 0 0 4px;
  color: var(--accent-deep);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.panel-heading h2,
.document-head h2 {
  margin: 0;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 21px;
  letter-spacing: -0.025em;
}

.panel-count {
  display: inline-flex;
  width: 29px;
  height: 29px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
}

.version-timeline {
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  padding: 18px 14px 8px 20px;
}

.version-timeline :deep(.el-timeline-item__timestamp) {
  color: var(--text-faint);
  font-size: 10px;
}

.ver {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 4px;
  padding: 11px 12px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.ver:hover {
  border-color: var(--line-soft);
  background: var(--surface-deep);
}

.ver.active {
  border-color: rgba(31, 75, 62, 0.17);
  background: var(--brand-soft);
}

.ver-title {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  font-size: 13px;
  font-weight: 700;
}

.ver-note {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.55;
}

.ver-meta {
  color: var(--text-faint);
  font-size: 10.5px;
}

.document-panel {
  overflow: hidden;
}

.document-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 22px 16px;
  border-bottom: 1px solid var(--line-soft);
}

.document-head h2 span {
  margin-left: 7px;
  color: var(--text-faint);
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0;
}

.editing-hint {
  display: block;
  margin-top: 5px;
  color: var(--warning);
  font-size: 11px;
}

.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.change-note { width: 190px; }
.actions :deep(.el-button) { margin-left: 0; }

.content-view {
  min-height: 480px;
  padding: 34px 42px 42px;
  background:
    linear-gradient(rgba(255, 252, 247, 0.93), rgba(255, 252, 247, 0.93)),
    repeating-linear-gradient(0deg, transparent 0 31px, rgba(112, 95, 70, 0.075) 31px 32px);
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 2.08;
}

.document-editor :deep(.el-textarea__inner) {
  min-height: 520px !important;
  padding: 30px 36px;
  border-radius: 0;
  box-shadow: none !important;
  background:
    linear-gradient(rgba(255, 252, 247, 0.93), rgba(255, 252, 247, 0.93)),
    repeating-linear-gradient(0deg, transparent 0 31px, rgba(112, 95, 70, 0.075) 31px 32px);
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 2.08;
}

.document-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 18px;
  border-top: 1px solid var(--line-soft);
  background: var(--surface-deep);
  color: var(--text-faint);
  font-size: 10.5px;
}

@media (max-width: 980px) {
  .detail-grid { grid-template-columns: 1fr; }
  .versions-panel { position: static; }
  .version-timeline {
    max-height: 250px;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 4px 12px;
  }
  .version-timeline :deep(.el-timeline-item) { padding-bottom: 12px; }
}

@media (max-width: 700px) {
  .studio-page-head { align-items: flex-start; }
  .studio-page-actions { width: 100%; }
  .studio-page-actions :deep(.el-button) { width: 100%; }
  .detail-grid { gap: 12px; }
  .version-timeline { grid-template-columns: 1fr; max-height: 290px; }
  .document-head {
    align-items: stretch;
    flex-direction: column;
    gap: 14px;
    padding: 18px 16px 14px;
  }
  .actions { justify-content: stretch; }
  .actions :deep(.el-button) { flex: 1 1 130px; }
  .change-note { width: 100%; flex: 1 1 100%; }
  .content-view { min-height: 380px; padding: 24px 20px 32px; font-size: 14px; }
  .document-editor :deep(.el-textarea__inner) { min-height: 420px !important; padding: 22px 18px; font-size: 14px; }
  .document-foot { align-items: flex-start; flex-direction: column; gap: 4px; }
}
</style>