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
    <!--
      ★ 返回按钮提到页面最顶部。
        原来它藏在左侧卡片的 header 里（"返回"小字），
        不够显眼 —— 用户找不到。
    -->
    <BackButton to="/resumes" label="返回简历列表" />

    <!--
      ★ v-if="store.current" 防止数据还没加载时渲染报错。
        骨架屏用 el-skeleton 更友好。
    -->
    <el-skeleton v-if="!store.current" :rows="8" animated />

    <el-row v-else :gutter="16">
      <!-- ═══════════ 左侧：版本时间线 ═══════════ -->
      <el-col :span="6">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <div class="head-left">
                <span class="head-title">{{ store.current.title }}</span>
                <!--
                  ★ 目标岗位：详情页也要显示，让用户确认"打开的是投哪个岗位的简历"
                    没有岗位时显示一个可点的浅色提示
                -->
                <span v-if="store.current.targetPosition" class="position-tag">
                  {{ store.current.targetPosition }}
                </span>
                <span v-else class="position-tag muted" @click="editInfoVisible = true">
                  未设岗位
                </span>
              </div>

              <div class="head-actions">
                <el-button text size="small" @click="openEditInfo">编辑信息</el-button>
              </div>
            </div>
          </template>

          <el-timeline>
            <!--
              ★ :type 决定时间线圆点的颜色
                 当前版本用 primary（蓝），其他用默认灰
            -->
            <el-timeline-item
              v-for="v in store.current.versions"
              :key="v.id"
              :timestamp="v.createdAt"
              placement="top"
              :type="v.id === store.current.currentVersionId ? 'primary' : ''"
            >
              <div
                class="ver"
                :class="{ active: v.id === activeVersionId }"
                @click="activeVersionId = v.id"
              >
                <div class="ver-title">
                  v{{ v.versionNo }}
                  <el-tag size="small" :type="sourceType[v.source]" effect="plain">
                    {{ sourceLabel[v.source] || v.source }}
                  </el-tag>
                  <!--
                    ★ 给当前版本加个"当前"标记
                      这样用户知道"分析默认会用哪一版"
                  -->
                  <el-tag
                    v-if="v.id === store.current.currentVersionId"
                    size="small"
                    type="primary"
                    effect="dark"
                  >
                    当前
                  </el-tag>
                </div>
                <div class="ver-note">{{ v.changeNote || '—' }}</div>
                <div class="ver-meta">{{ v.charCount }} 字</div>
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>

      <!-- ═══════════ 右侧：版本正文 ═══════════ -->
      <el-col :span="18">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>
                v{{ version?.versionNo }} · {{ version?.charCount }} 字
                <span v-if="editing" class="editing-hint">
                  （编辑中 {{ draftCharCount }} 字<span v-if="!enoughChars">
                  ，至少需 {{ MIN_CHARS }} 字</span>）
                </span>
              </span>

              <div class="actions">
                <!-- 浏览态 -->
                <template v-if="!editing">
                  <el-button @click="startEdit">编辑内容</el-button>
                  <el-button type="primary" disabled>发起分析</el-button>
                </template>

                <!-- 编辑态 -->
                <template v-else>
                  <el-input
                    v-model="changeNote"
                    placeholder="变更说明（可选）"
                    style="width: 200px; margin-right: 8px"
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
            </div>
          </template>

          <el-skeleton v-if="versionLoading" :rows="10" animated />

          <!--
            ★ v-if / v-else 切换编辑态和浏览态
            ★ 编辑态用 textarea，浏览态用 pre-wrap 保持换行
          -->
          <el-input
            v-else-if="editing"
            v-model="draft"
            type="textarea"
            :rows="26"
          />

          <!--
            ★ class="pre-wrap" 定义在 styles/index.css：
                white-space: pre-wrap   保留换行和空格
                word-break: break-word  长单词自动换行
              ★ 不用 v-html —— 用户内容一律当纯文本渲染，防 XSS。
          -->
          <div v-else class="pre-wrap content-view">{{ version?.content }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ═══════════ 编辑简历信息弹窗 ═══════════ -->
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
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.head-title {
  font-weight: 600;
  font-size: 14px;
  color: #10111a;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 目标岗位标签（同列表页的样式，保持一致） */
.position-tag {
  flex-shrink: 0;
  font-size: 12px;
  color: #4f46e5;
  background: #eef2ff;
  padding: 2px 8px;
  border-radius: 4px;
}

.position-tag.muted {
  color: #b0b3bd;
  background: #f7f8fa;
  cursor: pointer;
}

.position-tag.muted:hover {
  color: #6b7280;
  background: #eef0f4;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.actions {
  display: flex;
  align-items: center;
}

.ver {
  cursor: pointer;
  padding: 6px 8px;
  border-radius: 6px;
  transition: background 0.15s;
}

.ver:hover {
  background: #f5f7fa;
}

/* ★ 选中的版本高亮，让用户知道"现在看的是哪一版" */
.ver.active {
  background: #ecf5ff;
}

.ver-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 14px;
}

.ver-note {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.ver-meta {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 2px;
}

.editing-hint {
  font-size: 12px;
  color: #e6a23c;
  font-weight: normal;
}

.content-view {
  font-size: 14px;
  line-height: 1.8;
  min-height: 400px;
}
</style>
