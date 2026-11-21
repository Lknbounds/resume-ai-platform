package com.jianli.analysis.service;

import com.jianli.ai.model.JdParsed;
import com.jianli.ai.model.ResumeParsed;
import com.jianli.analysis.calculator.DimensionScore;
import com.jianli.analysis.calculator.ScoreResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  生成"优势"列表。★ 这是报告里最容易被忽略、但最能改变观感的一块。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么必须有"优势"？】
 *
 *  之前的报告只有：分数 + 六个维度 + 一串问题。
 *  用户看完的感受是"**我处处不行**"。
 *
 *  ★ 但真实的评估报告应该同时说"你哪里不错"和"哪里要改"。
 *    ResumeIQ 的报告之所以看起来"正式"，很大一部分原因是它有
 *    💪 Strengths Detected 这一块 —— 先肯定，再提建议。
 *
 *  【★ 优势必须可验证】
 *
 *  反例："你很有潜力" —— 无法验证，是空话。
 *  正例："有 2 段工作经历，累计 3.0 年" —— 从简历里数出来的事实。
 *
 *  ★ 每条优势都要能指向简历里的具体内容。
 *    这和"建议必须有证据"是同一条原则的两面。
 */
@Service
public class StrengthService {

    /** 最多给几条。太多会稀释重点 */
    private static final int MAX_ITEMS = 6;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【生成优势列表】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param score   评分结果（用来看哪些维度表现好）
     * @param jd      JD 解析结果
     * @param resume  简历解析结果
     * @return 优势描述列表，按重要性排序
     */
    public List<String> build(ScoreResult score, JdParsed jd, ResumeParsed resume) {
        List<String> out = new ArrayList<>();

        // ─────────── ① 技能命中 ───────────
        long hitCount = score.skillMatches().stream()
                .filter(m -> m.matchValue() >= 1.0).count();
        int totalSkill = score.skillMatches().size();

        if (totalSkill > 0 && hitCount >= totalSkill * 0.6) {
            out.add("技能匹配度高：JD 要求的 %d 项技能中命中 %d 项"
                    .formatted(totalSkill, hitCount));
        } else if (hitCount >= 3) {
            out.add("具备 %d 项岗位要求的核心技能".formatted(hitCount));
        }

        // ─────────── ② 工作经验 ───────────
        int expCount = resume.experiences().size();
        double years = resume.totalYears() == null ? 0 : resume.totalYears();

        if (expCount >= 2) {
            out.add("有 %d 段工作经历，累计 %.1f 年".formatted(expCount, years));
        } else if (expCount == 1 && years > 0) {
            out.add("有 %.1f 年的相关工作经验".formatted(years));
        }

        // ─────────── ③ 项目经验 ───────────
        if (!resume.projects().isEmpty()) {
            DimensionScore project = score.dimension("projectRelevance").orElse(null);
            if (project != null && project.score() >= 50) {
                out.add("项目与目标岗位技术栈重合度较高（%d 个项目）"
                        .formatted(resume.projects().size()));
            } else {
                out.add("有 %d 个项目经历".formatted(resume.projects().size()));
            }
        }

        // ─────────── ④ 量化成果 ───────────
        long quantified = resume.experiences().stream()
                .flatMap(e -> e.bullets().stream())
                .filter(ResumeParsed.ExperienceItem.Bullet::quantified)
                .count();
        if (quantified >= 2) {
            out.add("有 %d 处量化成果，描述具体可信".formatted(quantified));
        }

        // ─────────── ⑤ 学历达标 ───────────
        DimensionScore edu = score.dimension("educationMatch").orElse(null);
        if (edu != null && edu.applicable() && edu.score() >= 100) {
            out.add("学历满足岗位要求");
        }

        // ─────────── ⑥ 结构完整 ───────────
        DimensionScore structure = score.dimension("structureCompleteness").orElse(null);
        if (structure != null && structure.score() >= 80) {
            out.add("简历结构清晰，主要板块齐全");
        }

        // ─────────── ⑦ 经验年限达标 ───────────
        DimensionScore exp = score.dimension("experienceMatch").orElse(null);
        if (exp != null && exp.applicable() && exp.score() >= 100) {
            out.add("工作经验年限达到岗位要求");
        }

        // ─────────── ⑧ 加分项 ───────────
        if (score.bonus() > 0) {
            out.add("具备岗位的加分技能");
        }

        /*
         * ★ 如果一条优势都没找到怎么办？
         *
         *   说明简历确实很薄弱（技能没命中、没经历、没项目）。
         *   ★ 这时候【不要硬编一个】"你很努力"之类的空话 ——
         *     那是自欺欺人。
         *     返回空列表，前端显示"暂未发现明显优势，建议先补齐基础信息"。
         */
        return out.stream().limit(MAX_ITEMS).toList();
    }
}
