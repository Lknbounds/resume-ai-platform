package com.jianli.analysis.service;

import com.jianli.ai.model.JdParsed;
import com.jianli.ai.model.ResumeParsed;
import com.jianli.analysis.calculator.DimensionScore;
import com.jianli.analysis.calculator.ScoreResult;
import com.jianli.analysis.calculator.SkillMatch;
import com.jianli.analysis.dto.AnalysisReportVO.SuggestionVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  改进建议生成器。★ 规则版 —— 不依赖 AI。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 每条建议必须是"问题 + 证据 + 方向"三段式】
 *
 *  反例（没用）：
 *      "你的项目描述缺少量化"
 *      → 用户：我知道缺，但缺在哪一句？
 *
 *  正例（有用）：
 *      issue:      "经历描述缺少可验证的量化结果"
 *      evidence:   "负责订单系统开发与维护"        ← 简历原文
 *      suggestion: "补上指标：性能（QPS/耗时）、规模（单量/数据量）、
 *                   结果（提升百分比），并说明你的职责边界"
 *      → 用户：哦，是这句，我知道怎么改了
 *
 *  ★ evidence 必须【逐字来自简历原文】。
 *    这条规则将来接 AI 时同样适用 —— 见文档 05 的"AI 重写的严格校验"。
 *
 *  【★ 为什么建议也要排序？】
 *    按严重程度：HIGH 在前。
 *    用户只有精力改 2-3 条最重要的，不能让他自己在 10 条里挑。
 */
@Service
public class SuggestionService {

    /** 最多给几条建议。太多等于没有重点 */
    private static final int MAX_ITEMS = 8;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【生成建议】
     * ═══════════════════════════════════════════════════════════════
     */
    public List<SuggestionVO> build(ScoreResult score, JdParsed jd,
                                     ResumeParsed resume, String resumeText) {

        List<SuggestionVO> out = new ArrayList<>();

        // ─────────── ① 缺失的核心技能（最重要） ───────────
        for (SkillMatch m : score.skillMatches()) {
            if (m.isMissing() && "HIGH".equals(m.importance())) {
                out.add(new SuggestionVO(
                        "SKILLS",
                        "HIGH",
                        "JD 明确要求的「%s」在简历中未找到".formatted(m.skill()),
                        null,
                        "如果确实用过，请写明具体场景（做了什么、解决什么问题）；"
                                + "若没用过，考虑在项目中补充相关实践，或如实评估是否匹配这个岗位"
                ));
            }
        }

        // ─────────── ② 部分命中的技能（写法问题） ───────────
        score.skillMatches().stream()
                .filter(SkillMatch::isPartial)
                .limit(2)
                .forEach(m -> out.add(new SuggestionVO(
                        "SKILLS",
                        "MEDIUM",
                        "「%s」只做了模糊匹配，招聘方可能识别不到".formatted(m.skill()),
                        m.evidence(),
                        "把技能名写成 JD 里的标准写法，例如用「Kubernetes」而不是「k8s 集群运维」"
                )));

        // ─────────── ③ 量化不足 ───────────
        DimensionScore quantified = score.dimension("quantifiedImpact").orElse(null);
        if (quantified != null && quantified.applicable() && quantified.score() < 60) {
            String evidence = firstNonQuantifiedBullet(resume);
            out.add(new SuggestionVO(
                    "EXPERIENCE",
                    "HIGH",
                    "经历描述缺少可验证的量化结果",
                    evidence,
                    "补上三类指标：性能（QPS / 响应耗时）、规模（单量 / 数据量）、"
                            + "结果（提升百分比）；并说清哪部分是你负责的"
            ));
        }

        // ─────────── ④ 结构缺失 ───────────
        DimensionScore structure = score.dimension("structureCompleteness").orElse(null);
        if (structure != null && structure.detail() != null
                && structure.detail().contains("缺少板块")) {
            out.add(new SuggestionVO(
                    "OTHER",
                    "MEDIUM",
                    structure.detail(),
                    null,
                    "补齐缺失板块的标题（如「项目经历」「教育背景」）—— "
                            + "ATS 系统靠标题做分区解析，没有标题的内容可能被整段丢弃"
            ));
        }

        // ─────────── ⑤ 项目相关度低 ───────────
        DimensionScore project = score.dimension("projectRelevance").orElse(null);
        if (project != null && project.score() < 40) {
            String projectName = resume.projects().isEmpty()
                    ? null : resume.projects().get(0).name();
            out.add(new SuggestionVO(
                    "PROJECT",
                    "HIGH",
                    "项目与目标岗位的技术栈重合度低",
                    projectName,
                    "优先展示与 JD 技术栈最接近的项目，并在项目描述里点名 JD 的关键词——"
                            + "招聘方筛简历时按关键词匹配，不写等于没有"
            ));
        }

        // ─────────── ⑥ 年限不足 ───────────
        DimensionScore exp = score.dimension("experienceMatch").orElse(null);
        if (exp != null && exp.applicable() && exp.score() < 70) {
            out.add(new SuggestionVO(
                    "EXPERIENCE",
                    "MEDIUM",
                    exp.detail(),
                    null,
                    "若实际经验接近要求，可在简历里明确写出起止时间（如 2022.07-至今）；"
                            + "实习 / 开源 / 外包项目也可计入相关经验"
            ));
        }

        // ─────────── ⑦ 加分项未体现 ───────────
        if (!jd.niceToHaveSkills().isEmpty() && score.bonus() == 0) {
            String niceNames = jd.niceToHaveSkills().stream()
                    .map(s -> s.name()).limit(3)
                    .reduce((a, b) -> a + "、" + b).orElse("");
            out.add(new SuggestionVO(
                    "SKILLS",
                    "LOW",
                    "JD 提到的加分项（%s）均未体现".formatted(niceNames),
                    null,
                    "哪怕只做过一点，也可以写进「了解」类条目——面试时能展开说就行"
            ));
        }

        // ★ 截断到上限。前面的自然更严重（按上面的顺序排的）
        return out.stream().limit(MAX_ITEMS).toList();
    }

    /**
     * 找第一条"没量化"的工作描述，作为证据。
     *
     * ★ 为什么要找"没量化"的？
     *   建议是"你缺量化"，证据当然应该是"某条没量化的描述"。
     *   拿一条已经量化的当证据，逻辑就矛盾了。
     */
    private String firstNonQuantifiedBullet(ResumeParsed resume) {
        return resume.experiences().stream()
                .flatMap(e -> e.bullets().stream())
                .filter(b -> !b.quantified())
                .map(ResumeParsed.ExperienceItem.Bullet::text)
                .findFirst()
                .orElse(null);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【生成一句话总结】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 借鉴 ResumeIQ 的做法：分数旁边配一句话，
     *   告诉用户"这个分数意味着什么、下一步该做什么"。
     *   只有分数没有解读，用户不知道该高兴还是该焦虑。
     */
    public String summary(ScoreResult score, JdParsed jd) {
        double s = score.overallScore();
        long highMissing = score.skillMatches().stream()
                .filter(m -> m.isMissing() && "HIGH".equals(m.importance()))
                .count();

        if (s >= 85) {
            return "与目标岗位高度匹配。可以针对面试准备项目细节和量化数据。";
        }
        if (s >= 70) {
            return highMissing > 0
                    ? "整体匹配良好，补齐 %d 项核心技能后更有竞争力。".formatted(highMissing)
                    : "整体匹配良好，补齐细节后可以投递。";
        }
        if (s >= 55) {
            return "有一定基础，但技能缺口较明显。建议优先补齐核心技能并在项目中体现。";
        }
        return "与目标岗位差距较大。建议先对照 JD 补足技能，或考虑更匹配的方向。";
    }
}
