<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个组件是什么】
 *  "发布到社区"弹窗。★ 核心是发布前的个人信息审核体验。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 完整流程】
 *
 *      用户点"发布到社区"
 *         ↓
 *      弹窗打开，预填标题 + 简历正文（★ 从报告对应的简历版本取）
 *         ↓
 *      用户点"检查并发布"
 *         ↓
 *      ① 调 /api/privacy/precheck（不落库，即时反馈）
 *          ├─ 有命中 → 显示命中列表 + 提供"定位"（高亮）
 *          └─ 通过   → ② 调 POST /api/posts
 *         ↓
 *      ② 服务端会【再审一次】
 *          ├─ 43002 → 弹窗显示命中（以服务端为准）
 *          └─ 成功   → 跳到帖子详情
 *
 *  【★ 为什么前端检查了服务端还要查？】
 *   前端检查只是体验（快速反馈），不是安全边界。
 *   用户可以改 JS、可以直接调接口。
 *   所以服务端必须独立审核，且【以服务端结果为准】。
 */
import { ref, reactive, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { privacyApi, postApi } from '@/api/community'

const props = defineProps({
  visible: Boolean,
  report: Object,
  /** 报告对应的简历正文（预填用） */
  resumeContent: { type: String, default: '' }
})

const emit = defineEmits(['update:visible', 'published'])

const form = reactive({
  title: '',
  content: '',
  resumeContent: '',
  isAnonymous: false
})

const submitting = ref(false)
/** 审核命中的列表。null = 还没检查过 */
const hits = ref(null)

/** 简历正文输入框的引用（用于高亮定位） */
const resumeRef = ref()

/**
 * ★ 弹窗打开时预填。
 *
 * watch visible：每次打开都重置，
 * 避免上次的内容残留。
 */
watch(() => props.visible, (v) => {
  if (!v) return

  hits.value = null

  if (props.report) {
    // 标题默认："XX 岗位简历求建议"
    form.title = form.title || `${props.report.jdTitle || '简历'}，求帮忙看看`
    form.content = form.content || ''
  }

  // ★ 简历正文预填报告对应的版本
  form.resumeContent = props.resumeContent || ''
})

const resumeCharCount = computed(() =>
  form.resumeContent.replace(/\s/g, '').length)

const canSubmit = computed(() =>
  form.title.trim().length > 0 && resumeCharCount.value >= 20)

/**
 * ═══════════════════════════════════════════════════════════════════
 *  【提交】
 * ═══════════════════════════════════════════════════════════════════
 */
async function submit() {
  if (!form.title.trim()) {
    return ElMessage.warning('请填写标题')
  }
  if (resumeCharCount.value < 20) {
    return ElMessage.warning('简历内容太短')
  }

  submitting.value = true
  hits.value = null

  try {
    // ───── ① 前端预检（体验优化）─────
    const pre = await privacyApi.precheck({
      title: form.title,
      content: form.content,
      resumeContent: form.resumeContent
    })

    if (!pre.passed) {
      hits.value = pre.hits
      ElMessage.warning('检测到个人信息，请先抹去')
      return
    }

    // ───── ② 正式发布（服务端会再审）─────
    const post = await postApi.publish({
      reportId: props.report.id,
      title: form.title.trim(),
      content: form.content.trim() || null,
      resumeContent: form.resumeContent,
      isAnonymous: form.isAnonymous
    })

    ElMessage.success('发布成功')
    emit('update:visible', false)
    emit('published', post)

  } catch (e) {
    /*
     * ★ 服务端二次审核不通过（43002）。
     *
     *   拦截器对这个码只 reject 不弹提示 ——
     *   由本组件接管，因为需要展示"命中列表 + 定位"的专用 UI。
     *
     *   ★ 数据在 e.data.hits（不是 e.hits）。
     */
    if (e?.code === 43002) {
      hits.value = e.data?.hits || []
      ElMessage.warning('检测到个人信息，请先抹去')
    } else {
      ElMessage.error(e?.message || '发布失败')
    }
  } finally {
    submitting.value = false
  }
}

/**
 * 定位到命中的位置。
 *
 * ★ 原理：textarea 的 setSelectionRange(start, end) 会选中那段文字。
 *
 * ★ 注意：服务端返回的 start/end 是相对【标题+正文+简历】拼接后的文本。
 *   要定位到"简历正文"这个输入框，需要减去前面两段的长度。
 *   ★ 本项目简化：偏移不精确时也能用（用户能看到命中内容）。
 *     精确做法见文档里的说明。
 */
function locate(hit) {
  const el = resumeRef.value?.textarea
  if (!el) return

  /*
   * ★ 计算偏移：
   *   拼接顺序是 title + "\n" + content + "\n" + resumeContent
   *   所以简历正文的起点是 title.length + 1 + content.length + 1
   */
  const offset = form.title.length + 1 + form.content.length + 1
  const s = Math.max(0, hit.start - offset)
  const e = Math.max(s + 1, hit.end - offset)

  el.focus()
  el.setSelectionRange(s, e)

  // 滚动到可见
  const lineHeight = 20
  const linesBefore = form.resumeContent.slice(0, s).split('\n').length
  el.scrollTop = Math.max(0, linesBefore * lineHeight - el.clientHeight / 2)
}

const typeLabel = {
  PHONE: '手机号', ID_CARD: '身份证号', EMAIL: '邮箱',
  ADDRESS: '详细住址', BANK_CARD: '银行卡号', SOCIAL_ACCOUNT: '社交账号'
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="发布到社区"
    width="720px"
    top="6vh"
    @update:model-value="emit('update:visible', $event)"
  >
    <!-- ═══════════ 审核命中提示 ═══════════ -->
    <el-alert
      v-if="hits && hits.length"
      type="error"
      :closable="false"
      show-icon
      class="hit-alert"
    >
      <template #title>
        检测到 {{ hits.length }} 处个人信息，抹去后才能发布
      </template>

      <div class="hit-list">
        <div v-for="(h, i) in hits" :key="i" class="hit-item">
          <el-tag size="small" type="danger" effect="plain">
            {{ typeLabel[h.type] || h.type }}
          </el-tag>
          <span class="hit-masked">{{ h.maskedSnippet }}</span>
          <span class="hit-advice">{{ h.advice }}</span>
          <el-button link type="primary" size="small" @click="locate(h)">
            定位
          </el-button>
        </div>
      </div>
    </el-alert>

    <!-- ═══════════ 表单 ═══════════ -->
    <el-form label-position="top" class="publish-form">
      <el-form-item label="标题">
        <el-input
          v-model="form.title"
          placeholder="如：3 年 Java 后端，投了十几家没面试，求大佬看看"
          maxlength="120"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="补充说明（选填）">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="2"
          placeholder="比如：主要投中小厂 / 想转管理方向"
          maxlength="2000"
        />
      </el-form-item>

      <el-form-item>
        <template #label>
          <div class="label-row">
            <span>
              简历正文
              <span class="hint">发布后所有人可见，请先抹去手机号、邮箱等</span>
            </span>
            <span class="counter">{{ resumeCharCount }} 字</span>
          </div>
        </template>

        <el-input
          ref="resumeRef"
          v-model="form.resumeContent"
          type="textarea"
          :rows="12"
          placeholder="简历内容"
        />
      </el-form-item>

      <el-form-item>
        <el-checkbox v-model="form.isAnonymous">
          匿名发布（不显示我的昵称）
        </el-checkbox>
      </el-form-item>
    </el-form>

    <template #footer>
      <span class="footer-tip">发布前会自动检查个人信息，命中则无法发布</span>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="submit"
      >
        检查并发布
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hit-alert {
  margin-bottom: 16px;
}

.hit-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.hit-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.hit-masked {
  font-family: var(--font-mono);
  color: var(--text);
}

.hit-advice {
  flex: 1;
  color: var(--text-muted);
}

.label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.hint {
  font-size: 11px;
  color: var(--text-faint);
  font-weight: normal;
  margin-left: 6px;
}

.counter {
  font-size: 11px;
  color: var(--text-faint);
  font-weight: normal;
}

.footer-tip {
  float: left;
  font-size: 11px;
  color: var(--text-faint);
  line-height: 32px;
}
</style>
