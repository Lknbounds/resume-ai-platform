<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  首页。★ 只有介绍，没有功能列表。
 *
 *  【★ 视觉参考 getresumeiq.com】
 *      大标题用品牌渐变（青绿 → 青）
 *      副标题灰色，一句话说清
 *      两个入口按钮
 *      下面三条能力，用大数字分隔
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么首页不放功能入口？】
 *
 *  用户的诉求是"干净"。功能入口在顶部导航栏已经有了（分析/简历/JD/论坛），
 *  首页再放一遍是重复。
 *
 *  首页唯一的职责：让第一次来的人 3 秒内知道"这是什么、能干什么"。
 *  多放一个按钮，就多一份视觉噪音。
 */
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { useRouter } from 'vue-router'

const router = useRouter()

/**
 * 三条核心能力。
 *
 * ★ 为什么只写三条？
 *   人的短期记忆是 3-4 个条目。写 6 条等于一条都记不住。
 * ★ 每条只写"做什么"，不写"为什么好"（那是营销话术）。
 */
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

/**
 * 三项"比别人多出来的东西"。
 *
 * ★ 这是用户明确提到的差异化：
 *      比 ResumeIQ 多了"简历管理"和"讨论平台"
 *   所以单独用一行强调，而不是混进上面的能力里。
 */
const highlights = [
  { label: '简历管理', desc: '一份简历多个版本，按岗位归档' },
  { label: '社区互评', desc: '真实建议，可采纳并回写新版本' },
  { label: '数据留存', desc: '上传即保存，换设备也能找回' }
]
</script>

<template>
  <DefaultLayout>
    <!-- ═══════════ Hero ═══════════ -->
    <section class="hero">
      <h1 class="title">
        把简历<span class="text-gradient">讲清楚</span>
      </h1>

      <p class="lede">
        粘贴简历与目标岗位，得到结构化的匹配评分、缺失项与改进方向。<br />
        结果可以留在自己的简历库里，也可以拿去社区听听别人怎么说。
      </p>

      <div class="actions">
        <el-button type="primary" size="large" @click="router.push('/analysis/new')">
          开始分析
        </el-button>
        <el-button size="large" @click="router.push('/community')">
          看看别人怎么写
        </el-button>
      </div>
    </section>

    <!-- ═══════════ 三条能力 ═══════════ -->
    <section class="grid">
      <div v-for="c in capabilities" :key="c.no" class="item">
        <div class="item-no">{{ c.no }}</div>
        <h3 class="item-title">{{ c.title }}</h3>
        <p class="item-desc">{{ c.desc }}</p>
      </div>
    </section>

    <!-- ═══════════ 差异化说明 ═══════════ -->
    <section class="highlights">
      <div v-for="h in highlights" :key="h.label" class="hl">
        <span class="hl-dot" />
        <span class="hl-label">{{ h.label }}</span>
        <span class="hl-desc">{{ h.desc }}</span>
      </div>
    </section>
  </DefaultLayout>
</template>

<style scoped>
/* ═══════════════ Hero ═══════════════ */

.hero {
  max-width: 620px;
  margin: 0 auto;
  padding: 72px 0 64px;
  text-align: center;
}

.title {
  /*
   * ★ 40px + 负字距（letter-spacing: -0.03em）
   *   大标题加负字距会显得更紧凑、更有设计感。
   *   这是现代产品官网的通用做法（Linear / Vercel / Stripe 都这样）。
   */
  font-size: 40px;
  font-weight: 800;
  line-height: 1.2;
  letter-spacing: -0.03em;
  color: var(--text);
  margin: 0 0 20px;
}

.lede {
  font-size: 15px;
  line-height: 1.85;
  color: var(--text-muted);
  margin: 0 0 32px;
}

.actions {
  display: flex;
  gap: 10px;
  justify-content: center;
  align-items: center;
}

/* ═══════════════ 三条能力 ═══════════════ */

.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 40px;

  max-width: 900px;
  margin: 0 auto;
  padding: 52px 0 48px;

  /* ★ 顶部细线分隔 hero 和能力区 */
  border-top: 1px solid var(--line-soft);
}

.item-no {
  /*
   * ★ 大号浅色数字作为视觉锚点，而不是图标。
   *   图标需要成套设计，数字是纯排版 —— 更干净、更稳。
   */
  font-size: 11px;
  font-weight: 700;
  color: var(--brand);
  letter-spacing: 0.1em;
  margin-bottom: 14px;

  /* 数字后面一条短线，增加设计感 */
  display: flex;
  align-items: center;
  gap: 8px;
}

.item-no::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--line-soft);
}

.item-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  margin: 0 0 9px;
}

.item-desc {
  font-size: 13px;
  line-height: 1.75;
  color: var(--text-muted);
  margin: 0;
}

/* ═══════════════ 差异化 ═══════════════ */

.highlights {
  display: flex;
  flex-direction: column;
  gap: 0;

  max-width: 900px;
  margin: 0 auto;

  /*
   * ★ 用一个浅色卡片包起来，和上面的能力区区分开。
   *   背景用 --brand-soft（浅青），暗示"这是我们的特色"。
   */
  background: var(--brand-soft);
  border-radius: var(--radius-lg);
  padding: 8px 24px;
}

.hl {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 12px 0;
}

/* 每个条目之间用极细的线分隔（最后一个不加） */
.hl + .hl {
  border-top: 1px solid rgba(41, 163, 153, 0.15);
}

.hl-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand);
  flex-shrink: 0;

  /* 让点和文字的第一行对齐 */
  transform: translateY(-3px);
}

.hl-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-deep);
  flex-shrink: 0;
}

.hl-desc {
  font-size: 13px;
  color: var(--text-muted);
}

/* ═══════════════ 窄屏 ═══════════════ */

@media (max-width: 760px) {
  .grid {
    grid-template-columns: 1fr;
    gap: 28px;
  }

  .title {
    font-size: 30px;
  }

  .hero {
    padding: 48px 0 40px;
  }

  .actions {
    flex-direction: column;
  }

  .actions .el-button {
    width: 100%;
    margin-left: 0;
  }

  .hl {
    flex-direction: column;
    gap: 4px;
  }

  .hl-dot {
    display: none;
  }
}
</style>
