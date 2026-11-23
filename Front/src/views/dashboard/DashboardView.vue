<script setup>
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const capabilities = [
  {
    no: '01',
    title: '结构化解析',
    desc: '把 JD 和简历抽成技能、年限、项目与量化结果，而不是一堆文字。'
  },
  {
    no: '02',
    title: '匹配评分',
    desc: '六个维度加权计算，每个分数都能追溯到具体依据。'
  },
  {
    no: '03',
    title: '社区互评',
    desc: '发布到社区，采纳别人的建议后重新分析，看分数变化。'
  }
]

const highlights = [
  { label: '简历管理', desc: '一份简历多个版本，按岗位归档' },
  { label: '社区互评', desc: '真实建议，可采纳并回写新版本' },
  { label: '数据留存', desc: '上传即保存，换设备也能找回' }
]

const workflow = [
  { no: '01', title: '读懂岗位', desc: '先看清对方真正要什么' },
  { no: '02', title: '核对证据', desc: '找出简历里已经有的答案' },
  { no: '03', title: '改到点上', desc: '把经历写成可验证的成果' }
]
</script>

<template>
  <DefaultLayout>
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow"><span /> RESUME REVIEW STUDIO</p>
        <h1>把优势，<br /><em>讲到点子上。</em></h1>
        <p class="lede">
          粘贴简历与目标岗位，得到结构化的匹配评分、缺失项与改进方向。
          结果留在自己的简历库里，也能去社区听听真实建议。
        </p>

        <div class="actions">
          <el-button type="primary" size="large" @click="router.push('/analysis/new')">
            开始一次分析
          </el-button>
          <el-button size="large" @click="router.push('/community')">
            看看别人怎么写
          </el-button>
        </div>

        <div class="trust-line">
          <span class="trust-mark">简</span>
          <span>不只给分，更告诉你下一步改哪里</span>
        </div>
      </div>

      <aside class="studio-sheet" aria-label="简历评审流程示意">
        <div class="sheet-topline">
          <span class="sheet-label">REVIEW NOTE · 01</span>
          <span class="sheet-status">准备中</span>
        </div>

        <div class="sheet-title-row">
          <span class="sheet-index">A</span>
          <div>
            <p>一份简历的成长路线</p>
            <h2>先对齐岗位，再重写证据。</h2>
          </div>
        </div>

        <div class="workflow">
          <div v-for="step in workflow" :key="step.no" class="workflow-step">
            <span class="step-no">{{ step.no }}</span>
            <div class="step-copy">
              <strong>{{ step.title }}</strong>
              <span>{{ step.desc }}</span>
            </div>
            <span class="step-line" aria-hidden="true" />
          </div>
        </div>

        <div class="sheet-foot">
          <span class="tape" aria-hidden="true" />
          <p>不是打分机器，是陪你把经历讲清楚。</p>
          <span class="sheet-sign">简评 · Career Studio</span>
        </div>
      </aside>
    </section>

    <section class="capabilities" aria-labelledby="capability-title">
      <div class="section-head">
        <div>
          <p class="eyebrow"><span /> THE WORKBENCH</p>
          <h2 id="capability-title">工作台能帮你做什么</h2>
        </div>
        <p>从读懂岗位，到把证据写进简历，再到听听同行怎么说。</p>
      </div>

      <div class="capability-grid">
        <article v-for="c in capabilities" :key="c.no" class="capability-card">
          <span class="capability-no">{{ c.no }}</span>
          <h3>{{ c.title }}</h3>
          <p>{{ c.desc }}</p>
          <span class="card-arrow" aria-hidden="true">↗</span>
        </article>
      </div>
    </section>

    <section class="highlights" aria-label="产品特色">
      <div v-for="h in highlights" :key="h.label" class="highlight">
        <span class="highlight-dot" aria-hidden="true" />
        <div>
          <strong>{{ h.label }}</strong>
          <span>{{ h.desc }}</span>
        </div>
      </div>
    </section>
  </DefaultLayout>
</template>

<style scoped>
.hero {
  display: grid;
  grid-template-columns: minmax(0, 1.02fr) minmax(380px, 0.98fr);
  align-items: center;
  gap: clamp(42px, 7vw, 94px);
  padding: 34px 0 74px;
}

.hero-copy { max-width: 620px; }

.eyebrow {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0 0 20px;
  color: var(--brand);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.eyebrow span {
  width: 22px;
  height: 1px;
  background: var(--accent);
}

h1 {
  margin: 0;
  color: var(--text);
  font-size: clamp(48px, 6vw, 76px);
  line-height: 1.06;
  letter-spacing: -0.055em;
  font-weight: 700;
}

h1 em {
  color: var(--brand);
  font-style: normal;
  position: relative;
}

h1 em::after {
  content: '';
  position: absolute;
  left: 2%;
  right: -1%;
  bottom: 2px;
  height: 9px;
  border-radius: 999px;
  background: rgba(242, 193, 91, 0.46);
  z-index: -1;
}

.lede {
  max-width: 560px;
  margin: 26px 0 30px;
  color: var(--text-muted);
  font-size: 15px;
  line-height: 1.9;
}

.actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.actions :deep(.el-button) {
  min-height: 48px;
  padding-inline: 22px;
}

.trust-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 30px;
  color: var(--text-faint);
  font-size: 12px;
}

.trust-mark {
  display: inline-flex;
  width: 25px;
  height: 25px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-family: var(--font-serif);
  font-weight: 700;
}

.studio-sheet {
  position: relative;
  padding: 30px;
  border-radius: 6px 22px 22px 22px;
  border: 1px solid var(--line);
  background:
    linear-gradient(rgba(255, 252, 247, 0.94), rgba(255, 252, 247, 0.94)),
    repeating-linear-gradient(0deg, transparent 0 31px, rgba(112, 95, 70, 0.08) 31px 32px);
  box-shadow: 20px 24px 0 rgba(31, 75, 62, 0.06), var(--shadow-md);
  transform: rotate(1.2deg);
}

.studio-sheet::before {
  content: '';
  position: absolute;
  width: 58px;
  height: 58px;
  right: -14px;
  top: -16px;
  border-radius: 50%;
  background: var(--gold);
  opacity: 0.5;
  filter: blur(1px);
}

.sheet-topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line-soft);
}

.sheet-label {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.14em;
}

.sheet-status {
  padding: 4px 9px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent-deep);
  font-size: 10px;
  font-weight: 700;
}

.sheet-title-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 28px 0 24px;
}

.sheet-index {
  display: inline-flex;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-family: var(--font-serif);
  font-size: 15px;
}

.sheet-title-row p {
  margin: 0 0 6px;
  color: var(--text-faint);
  font-size: 11px;
}

.sheet-title-row h2 {
  margin: 0;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 23px;
  line-height: 1.4;
  letter-spacing: -0.025em;
}

.workflow {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.workflow-step {
  display: grid;
  grid-template-columns: 34px 1fr 36px;
  align-items: center;
  gap: 12px;
  min-height: 66px;
  padding: 10px 14px;
  border-radius: 12px;
  background: rgba(248, 243, 233, 0.72);
  border: 1px solid rgba(221, 212, 197, 0.7);
}

.step-no {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--accent-deep);
  font-weight: 700;
}

.step-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.step-copy strong {
  color: var(--text);
  font-size: 13px;
}

.step-copy span {
  color: var(--text-muted);
  font-size: 11px;
}

.step-line {
  height: 1px;
  background: linear-gradient(90deg, var(--sage), transparent);
}

.sheet-foot {
  position: relative;
  margin-top: 26px;
  padding-top: 20px;
  border-top: 1px solid var(--line-soft);
}

.sheet-foot p {
  margin: 0 0 9px;
  color: var(--text-muted);
  font-family: var(--font-serif);
  font-size: 14px;
}

.sheet-sign {
  color: var(--text-faint);
  font-size: 9px;
  letter-spacing: 0.1em;
}

.tape {
  position: absolute;
  width: 64px;
  height: 18px;
  top: -10px;
  left: 50%;
  transform: translateX(-50%) rotate(-2deg);
  background: rgba(242, 193, 91, 0.42);
}

.capabilities {
  padding: 54px 0 58px;
  border-top: 1px solid var(--line);
}

.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 26px;
}

.section-head .eyebrow { margin-bottom: 10px; }

.section-head h2 {
  margin: 0;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 31px;
  letter-spacing: -0.03em;
}

.section-head > p {
  max-width: 390px;
  margin: 0 0 4px;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.75;
  text-align: right;
}

.capability-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.capability-card {
  position: relative;
  min-height: 192px;
  padding: 23px 22px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  background: var(--surface);
  overflow: hidden;
}

.capability-card:nth-child(2) {
  background: var(--brand-soft);
  border-color: rgba(31, 75, 62, 0.12);
}

.capability-no {
  display: block;
  margin-bottom: 26px;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.capability-card h3 {
  margin: 0 0 9px;
  color: var(--text);
  font-size: 16px;
}

.capability-card p {
  max-width: 290px;
  margin: 0;
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.75;
}

.card-arrow {
  position: absolute;
  right: 18px;
  top: 18px;
  color: var(--text-faint);
  font-size: 18px;
}

.highlights {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.highlight {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 22px 24px;
}

.highlight + .highlight { border-left: 1px solid var(--line); }

.highlight-dot {
  width: 7px;
  height: 7px;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--accent);
  flex-shrink: 0;
}

.highlight div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.highlight strong {
  color: var(--brand-deep);
  font-size: 12px;
}

.highlight span {
  color: var(--text-muted);
  font-size: 11px;
}

@media (max-width: 960px) {
  .hero { grid-template-columns: 1fr; max-width: 720px; margin-inline: auto; }
  .studio-sheet { transform: none; max-width: 620px; }
  .capability-grid { grid-template-columns: 1fr; }
  .capability-card { min-height: 0; }
  .section-head { align-items: flex-start; flex-direction: column; gap: 12px; }
  .section-head > p { text-align: left; }
}

@media (max-width: 720px) {
  .hero { padding: 16px 0 54px; gap: 38px; }
  h1 { font-size: clamp(42px, 12vw, 58px); }
  .lede { font-size: 14px; }
  .actions { align-items: stretch; flex-direction: column; }
  .actions :deep(.el-button) { width: 100%; margin: 0; }
  .studio-sheet { padding: 22px; box-shadow: 8px 10px 0 rgba(31, 75, 62, 0.06), var(--shadow-md); }
  .sheet-title-row h2 { font-size: 20px; }
  .workflow-step { grid-template-columns: 30px 1fr; }
  .step-line { display: none; }
  .highlights { grid-template-columns: 1fr; }
  .highlight + .highlight { border-left: 0; border-top: 1px solid var(--line); }
  .section-head h2 { font-size: 27px; }
}
</style>
