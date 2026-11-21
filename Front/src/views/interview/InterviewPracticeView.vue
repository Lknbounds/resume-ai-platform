<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import BackButton from '@/components/BackButton.vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { interviewApi } from '@/api/interview'

const loading = ref(true)
const loadError = ref('')
const questions = ref([])
const sourceLabel = ref('基础题库')
const revealed = ref(new Set())

const categoryLabels = {
  PROJECT: '项目',
  EXPERIENCE: '经历',
  TECHNICAL: '技术'
}

onMounted(loadQuestions)

async function loadQuestions() {
  loading.value = true
  loadError.value = ''
  revealed.value = new Set()

  try {
    const data = await interviewApi.questions()
    questions.value = data.questions || []
    sourceLabel.value = data.source === 'TEMPLATE' ? '基础题库' : 'AI 题库'

    if (questions.value.length !== 5) {
      throw new Error('题目数量不是 5 道')
    }
  } catch (error) {
    loadError.value = '面试题加载失败，请稍后重试'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function isRevealed(sortNo) {
  return revealed.value.has(sortNo)
}

function toggleAnswer(sortNo) {
  const next = new Set(revealed.value)
  if (next.has(sortNo)) {
    next.delete(sortNo)
  } else {
    next.add(sortNo)
  }
  revealed.value = next
}
</script>

<template>
  <DefaultLayout>
    <main class="interview-page">
      <BackButton to="/" label="返回首页" />

      <header class="page-header">
        <div>
          <div class="eyebrow">INTERVIEW PRACTICE</div>
          <h1>面试预演</h1>
          <p>先独立思考并口头回答，再逐题查看参考答案和回答要点。</p>
        </div>
        <div class="header-meta">
          <span class="count">5 道题</span>
          <span class="source">{{ sourceLabel }}</span>
        </div>
      </header>

      <section v-if="loading" class="loading-panel" aria-live="polite">
        <el-skeleton :rows="5" animated />
        <el-skeleton :rows="5" animated />
      </section>

      <div v-else-if="loadError" class="error-panel" role="alert">
        <el-icon><WarningFilled /></el-icon>
        <div>
          <h2>暂时无法加载题目</h2>
          <p>{{ loadError }}</p>
          <el-button type="primary" @click="loadQuestions">
            <el-icon><Refresh /></el-icon>
            重新加载
          </el-button>
        </div>
      </div>

      <section v-else class="question-list" aria-label="面试题列表">
        <article v-for="question in questions" :key="question.sortNo" class="question-card">
          <header class="question-header">
            <div class="question-main">
              <div class="question-meta">
                <span class="number">Q{{ question.sortNo }}</span>
                <span class="category">{{ categoryLabels[question.category] || '技术' }}</span>
              </div>
              <h2 :id="`question-${question.sortNo}`">{{ question.question }}</h2>
            </div>

            <button
              class="reveal-button"
              type="button"
              :aria-expanded="isRevealed(question.sortNo)"
              :aria-controls="`answer-${question.sortNo}`"
              @click="toggleAnswer(question.sortNo)"
            >
              <el-icon>
                <Hide v-if="isRevealed(question.sortNo)" />
                <View v-else />
              </el-icon>
              {{ isRevealed(question.sortNo) ? '隐藏答案' : '显示答案' }}
            </button>
          </header>

          <Transition name="answer">
            <section
              v-if="isRevealed(question.sortNo)"
              :id="`answer-${question.sortNo}`"
              class="answer-panel"
              :aria-labelledby="`question-${question.sortNo}`"
            >
              <div class="reference-answer">
                <h3>
                  <el-icon><Document /></el-icon>
                  参考答案
                </h3>
                <p>{{ question.answer }}</p>
              </div>

              <div class="support-info">
                <section class="support-block">
                  <h3>
                    <el-icon><Aim /></el-icon>
                    考察点
                  </h3>
                  <p>{{ question.intent }}</p>
                </section>

                <section class="support-block">
                  <h3>
                    <el-icon><List /></el-icon>
                    回答要点
                  </h3>
                  <ul>
                    <li v-for="(point, index) in question.keyPoints" :key="index">{{ point }}</li>
                  </ul>
                </section>

                <section v-if="question.followUp" class="support-block follow-up">
                  <h3>
                    <el-icon><ChatDotRound /></el-icon>
                    可能的追问
                  </h3>
                  <p>{{ question.followUp }}</p>
                </section>

                <section v-if="question.refNote" class="support-block reference">
                  <h3>
                    <el-icon><Collection /></el-icon>
                    题目依据
                  </h3>
                  <blockquote>{{ question.refNote }}</blockquote>
                </section>
              </div>
            </section>
          </Transition>
        </article>
      </section>
    </main>
  </DefaultLayout>
</template>


<style scoped>
.interview-page {
  width: min(920px, 100%);
  margin: 0 auto;
  padding: 24px 20px 56px;
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}

.eyebrow {
  margin-bottom: 7px;
  color: var(--brand-deep);
  font-size: 12px;
  font-weight: 750;
  letter-spacing: 0;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  color: var(--text);
  font-size: 30px;
  line-height: 1.25;
  letter-spacing: 0;
}

.page-header p {
  max-width: 620px;
  margin: 9px 0 0;
  color: var(--text-muted);
  font-size: 15px;
  line-height: 1.7;
}

.header-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.count,
.source {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  padding: 5px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.source {
  border-color: #bfe4e0;
  background: var(--brand-soft);
  color: var(--brand-deep);
}

.loading-panel {
  display: grid;
  gap: 16px;
  padding: 24px;
  border: 1px solid var(--line-soft);
  border-radius: 8px;
  background: var(--surface);
}

.error-panel {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 24px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fff;
  color: var(--danger);
}

.error-panel > .el-icon {
  margin-top: 3px;
  font-size: 22px;
}

.error-panel h2 {
  margin: 0;
  color: var(--text);
  font-size: 18px;
  letter-spacing: 0;
}

.error-panel p {
  margin: 7px 0 16px;
  color: var(--text-muted);
  line-height: 1.6;
}

.question-list {
  display: grid;
  gap: 16px;
}

.question-card {
  overflow: hidden;
  border: 1px solid var(--line-soft);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.question-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 20px;
  padding: 22px;
}

.question-main {
  min-width: 0;
}

.question-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.number {
  color: var(--brand-deep);
  font-size: 13px;
  font-weight: 800;
}

.category {
  padding: 3px 8px;
  border-radius: var(--radius-sm);
  background: var(--fill);
  color: var(--text-muted);
  font-size: 12px;
}

.question-header h2 {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text);
  font-size: 18px;
  line-height: 1.65;
  letter-spacing: 0;
}

.reveal-button {
  display: inline-flex;
  min-width: 112px;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 9px 13px;
  border: 1px solid #9fd3ce;
  border-radius: 7px;
  background: var(--surface);
  color: var(--brand-deep);
  font-family: inherit;
  font-size: 14px;
  font-weight: 650;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
}

.reveal-button:hover {
  border-color: var(--brand);
  background: var(--brand-soft);
}

.reveal-button:focus-visible {
  outline: 3px solid rgba(41, 163, 153, 0.28);
  outline-offset: 2px;
}

.answer-panel {
  padding: 0 22px 22px;
}

.reference-answer {
  padding-top: 20px;
  border-top: 1px solid var(--line-soft);
}

.reference-answer h3,
.support-block h3 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  color: var(--text);
  font-size: 14px;
  line-height: 1.5;
  letter-spacing: 0;
}

.reference-answer h3 {
  color: var(--brand-deep);
}

.reference-answer p {
  margin: 10px 0 0;
  color: var(--text);
  font-size: 15px;
  line-height: 1.85;
  white-space: pre-line;
}

.support-info {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 28px;
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid var(--line-soft);
}

.support-block {
  min-width: 0;
  padding: 12px 0;
}

.support-block p {
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.75;
}

.support-block ul {
  display: grid;
  gap: 7px;
  margin: 9px 0 0;
  padding-left: 19px;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.65;
}

.support-block blockquote {
  margin: 9px 0 0;
  padding: 8px 12px;
  border-left: 3px solid var(--brand);
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 13px;
  line-height: 1.7;
}

.answer-enter-active,
.answer-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.answer-enter-from,
.answer-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 700px) {
  .interview-page {
    padding: 18px 14px 42px;
  }

  .page-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 15px;
  }

  h1 {
    font-size: 25px;
  }

  .header-meta {
    justify-content: flex-start;
  }

  .question-header {
    grid-template-columns: 1fr;
    gap: 16px;
    padding: 18px;
  }

  .reveal-button {
    width: 100%;
  }

  .answer-panel {
    padding: 0 18px 18px;
  }

  .support-info {
    grid-template-columns: 1fr;
    gap: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .answer-enter-active,
  .answer-leave-active {
    transition: none;
  }
}
</style>