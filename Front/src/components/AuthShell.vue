<script setup>
import { useRouter } from 'vue-router'

defineProps({
  heading: { type: String, required: true },
  description: { type: String, default: '' },
  altText: { type: String, default: '' },
  altLinkLabel: { type: String, default: '' }
})
defineEmits(['alt'])

const router = useRouter()
</script>

<template>
  <div class="auth-page">
    <header class="site-header">
      <div class="site-brand" aria-label="简评 Career Studio">
        <span class="brand-mark" aria-hidden="true">简</span>
        <span class="brand-copy">
          <strong>简评</strong>
          <small>CAREER STUDIO</small>
        </span>
      </div>

      <span class="header-rule" aria-hidden="true" />
      <span class="header-note">RESUME REVIEW STUDIO</span>

      <button type="button" class="community-link" @click="router.push('/community')">
        先逛逛社区
        <span aria-hidden="true">↗</span>
      </button>
    </header>

    <main class="auth-shell">
      <aside class="story-panel">
        <div class="story-index">
          <span>REVIEW NOTES</span>
          <span>01 / 02</span>
        </div>

        <div class="story-main">
          <p class="story-eyebrow">A QUIET PLACE TO GET CLEARER</p>
          <h1>让每一段经历，<br />都有清晰的说法。</h1>
          <p class="story-lede">
            把岗位要求与自己的经历放在同一张桌上。
            看见差距，也看见你已经做对的部分。
          </p>

          <div class="story-notes" aria-label="简评的工作方式">
            <div class="note">
              <span>01</span>
              <p>不从模板开始，从你的真实经历开始。</p>
            </div>
            <div class="note">
              <span>02</span>
              <p>每一句建议，都对应岗位要求或简历原文。</p>
            </div>
            <div class="note">
              <span>03</span>
              <p>先肯定已有证据，再讨论下一步怎么补。</p>
            </div>
          </div>
        </div>

        <p class="story-foot">RESUME REVIEW · JOB MATCH · PEER FEEDBACK</p>
      </aside>

      <section class="form-panel">
        <div class="auth-card">
          <header class="auth-head">
            <div class="form-index">
              <span>MEMBER ACCESS</span>
              <span>02 / 02</span>
            </div>
            <h2>{{ heading }}</h2>
            <p>{{ description }}</p>
          </header>

          <slot />

          <footer v-if="altText" class="auth-foot">
            <span>{{ altText }}</span>
            <button type="button" class="alt-link" @click="$emit('alt')">
              {{ altLinkLabel }}
            </button>
          </footer>
        </div>

        <footer class="form-foot">
          <span>用更清楚的方式，讲清楚你的专业价值。</span>
          <span>JIANPING © 2026</span>
        </footer>
      </section>
    </main>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(circle at 8% 10%, rgba(31, 75, 62, 0.055), transparent 28rem),
    radial-gradient(circle at 92% 18%, rgba(238, 106, 75, 0.065), transparent 25rem),
    var(--paper);
  color: var(--text);
}

.site-header {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: auto minmax(40px, 1fr) auto auto;
  align-items: center;
  gap: 22px;
  min-height: 76px;
  padding: 0 clamp(28px, 5vw, 72px);
  border-bottom: 1px solid rgba(221, 212, 197, 0.9);
  background: rgba(248, 243, 233, 0.9);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.site-brand {
  display: flex;
  align-items: center;
  gap: 11px;
  flex-shrink: 0;
}

.brand-mark {
  display: flex;
  width: 38px;
  height: 38px;
  align-items: center;
  justify-content: center;
  border-radius: 13px;
  background: var(--gradient-accent);
  color: #fff;
  font-family: var(--font-serif);
  font-size: 20px;
  font-weight: 700;
  box-shadow: 0 7px 18px rgba(238, 106, 75, 0.24);
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 5px;
  line-height: 1;
}

.brand-copy strong {
  font-family: var(--font-serif);
  font-size: 20px;
  letter-spacing: 0.04em;
}

.brand-copy small {
  color: var(--text-faint);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.header-rule {
  height: 1px;
  background: var(--line-soft);
}

.header-note {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.15em;
  white-space: nowrap;
}

.community-link {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  gap: 8px;
  padding: 0 15px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 252, 247, 0.7);
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color 0.18s ease, color 0.18s ease, background-color 0.18s ease;
}

.community-link:hover {
  border-color: var(--brand);
  background: var(--surface);
  color: var(--brand-deep);
}

.community-link span {
  color: var(--accent);
  font-size: 14px;
}

.auth-shell {
  position: relative;
  width: min(1240px, calc(100vw - 96px));
  min-height: clamp(680px, calc(100vh - 152px), 780px);
  margin: clamp(22px, 3.4vh, 40px) auto clamp(26px, 4vh, 48px);
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(480px, 0.92fr);
  overflow: hidden;
  border: 1px solid rgba(221, 212, 197, 0.88);
  border-radius: 28px;
  background: var(--surface);
  box-shadow:
    0 26px 70px rgba(60, 47, 30, 0.11),
    0 4px 16px rgba(60, 47, 30, 0.055);
}

.story-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: clamp(34px, 3.8vw, 54px) clamp(42px, 4.8vw, 72px);
  overflow: hidden;
  background:
    radial-gradient(circle at 92% 8%, rgba(242, 193, 91, 0.24), transparent 18rem),
    radial-gradient(circle at 4% 96%, rgba(169, 194, 176, 0.18), transparent 22rem),
    var(--brand);
  color: #fdf8ed;
}

.story-panel::after {
  content: '';
  position: absolute;
  width: 280px;
  height: 280px;
  right: -118px;
  bottom: -128px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 50%;
  box-shadow:
    0 0 0 46px rgba(255, 255, 255, 0.035),
    0 0 0 94px rgba(255, 255, 255, 0.025);
  pointer-events: none;
}

.story-index {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding-bottom: 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.13);
  color: rgba(253, 248, 237, 0.46);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.14em;
}

.story-main {
  position: relative;
  z-index: 1;
  width: min(620px, 100%);
  margin: auto 0;
  padding: clamp(30px, 5.5vh, 62px) 0;
}

.story-eyebrow {
  margin: 0 0 18px;
  color: rgba(253, 248, 237, 0.57);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.2em;
}

.story-panel h1 {
  margin: 0;
  max-width: 600px;
  font-family: var(--font-serif);
  font-size: clamp(46px, 4.5vw, 68px);
  line-height: 1.15;
  letter-spacing: -0.055em;
  font-weight: 700;
}

.story-lede {
  max-width: 510px;
  margin: 22px 0 30px;
  color: rgba(253, 248, 237, 0.72);
  font-size: 15px;
  line-height: 1.9;
}

.story-notes {
  display: flex;
  flex-direction: column;
  max-width: 530px;
  border-top: 1px solid rgba(255, 255, 255, 0.14);
}

.note {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 10px;
  align-items: baseline;
  padding: 13px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
}

.note span {
  color: var(--gold);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
}

.note p {
  margin: 0;
  color: rgba(253, 248, 237, 0.8);
  font-size: 12px;
  line-height: 1.65;
}

.story-foot {
  position: relative;
  z-index: 1;
  margin: 0;
  color: rgba(253, 248, 237, 0.34);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.13em;
}

.form-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: clamp(36px, 4.8vh, 54px) clamp(44px, 6vw, 84px) 26px;
  border-left: 1px solid var(--line-soft);
  background:
    linear-gradient(rgba(252, 248, 239, 0.95), rgba(252, 248, 239, 0.95)),
    repeating-linear-gradient(0deg, transparent 0 35px, rgba(112, 95, 70, 0.07) 35px 36px);
}

.form-panel::before {
  content: '';
  position: absolute;
  top: 0;
  left: clamp(24px, 4vw, 60px);
  width: 54px;
  height: 3px;
  background: var(--accent);
}

.auth-card {
  width: min(470px, 100%);
  margin-top: auto;
  margin-bottom: auto;
}

.auth-head {
  margin-bottom: 28px;
}

.form-index {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding-bottom: 15px;
  border-bottom: 1px solid var(--line-soft);
  color: var(--accent-deep);
  font-family: var(--font-mono);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.form-index span:last-child {
  color: var(--text-faint);
}

.auth-head h2 {
  margin: 23px 0 9px;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 44px;
  line-height: 1.2;
  letter-spacing: -0.045em;
}

.auth-head > p {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.75;
}

.auth-foot {
  display: flex;
  justify-content: center;
  gap: 5px;
  margin-top: 22px;
  color: var(--text-muted);
  font-size: 12px;
}

.alt-link {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--brand);
  font-size: inherit;
  font-weight: 700;
  cursor: pointer;
}

.alt-link:hover {
  color: var(--accent-deep);
}

.form-foot {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-top: auto;
  padding-top: 34px;
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.06em;
}

@media (min-width: 821px) and (max-height: 800px) {
  .auth-shell {
    min-height: 650px;
    margin-block: 15px;
  }

  .form-panel {
    padding-top: 30px;
    padding-bottom: 20px;
  }

  .story-panel {
    padding-block: 30px;
  }
}

@media (min-width: 981px) and (max-width: 1100px) {
  .story-panel {
    padding-inline: 38px;
  }

  .story-panel h1 {
    font-size: 42px;
  }

  .form-panel {
    padding-inline: 38px;
  }
}

@media (max-width: 980px) {
  .header-note,
  .header-rule {
    display: none;
  }

  .site-header {
    grid-template-columns: 1fr auto;
  }

  .auth-shell {
    width: min(920px, calc(100vw - 56px));
    grid-template-columns: minmax(0, 0.92fr) minmax(440px, 1.08fr);
  }

  .story-panel {
    padding-inline: 36px;
  }

  .story-panel h1 {
    font-size: 46px;
  }

  .form-panel {
    padding-inline: 40px;
  }
}

@media (max-width: 820px) {
  .site-header {
    min-height: 68px;
    padding-inline: 20px;
  }

  .header-note,
  .header-rule {
    display: none;
  }

  .community-link {
    padding-inline: 12px;
  }

  .auth-shell {
    width: min(680px, calc(100vw - 32px));
    margin-block: 20px 28px;
    border-radius: 22px;
    display: block;
  }

  .story-panel {
    min-height: auto;
    padding: 26px 22px 30px;
  }

  .story-index,
  .story-main {
    width: min(520px, 100%);
    margin-inline: auto;
  }

  .story-main {
    padding: 34px 0 4px;
  }

  .story-panel h1 {
    font-size: 38px;
  }

  .story-lede {
    margin: 16px 0 0;
  }

  .story-notes,
  .story-foot {
    display: none;
  }

  .form-panel {
    min-height: 560px;
    padding: 38px 22px 26px;
    border-top: 1px solid var(--line-soft);
    border-left: 0;
  }

  .auth-card {
    width: min(440px, 100%);
    margin: 0 auto auto;
  }

  .form-foot {
    width: min(440px, 100%);
    margin-inline: auto;
  }
}

@media (max-width: 480px) {
  .brand-copy small {
    display: none;
  }

  .community-link {
    font-size: 11px;
  }

  .story-panel h1 {
    font-size: 33px;
  }

  .auth-head h2 {
    font-size: 34px;
  }

  .form-foot span:last-child {
    display: none;
  }
}
</style>
