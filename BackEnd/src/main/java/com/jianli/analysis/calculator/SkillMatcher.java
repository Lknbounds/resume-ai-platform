package com.jianli.analysis.calculator;

import com.jianli.ai.model.JdParsed;
import com.jianli.ai.model.ResumeParsed;
import com.jianli.ai.model.SkillItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  技能匹配器。把"JD 要求的技能"和"简历里出现的技能"对起来。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 匹配的四个档次（按可靠程度从高到低）】
 *
 *  ① EXACT —— 完全相同
 *       JD 要 "Java"，简历写 "Java"           → 1.0
 *
 *  ② ALIAS —— 别名命中
 *       JD 要 "Kubernetes"，简历写 "k8s"      → 1.0
 *       靠词典的别名表，所以"会"就是"会"，不该扣分
 *
 *  ③ LEXICAL —— 字面相似
 *       JD 要 "Spring Cloud"，简历写 "Spring Cloud Alibaba"
 *       包含关系 → 0.5
 *
 *  ④ MISSING —— 都没有                     → 0.0
 *
 *  ★ 为什么 ALIAS 给满分而 LEXICAL 只给半分？
 *    别名是【确定等价】的（k8s 就是 Kubernetes），
 *    字面相似只是【可能相关】——"Spring Cloud" 和
 *    "Spring Cloud Alibaba" 相关但不完全等同。
 *    置信度不同，分数就该不同。
 *
 *  ⚠️ 注意：这里【没有语义匹配】。
 *     语义匹配需要 embedding（调模型），属于 AI 能力。
 *     规则版只做前面三档 —— 这是"AI 关闭仍可用"的具体体现。
 */
@Component
@RequiredArgsConstructor
public class SkillMatcher {

    private final com.jianli.ai.parser.SkillDictionary dictionary;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【匹配入口】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param jd      JD 解析结果（提供 requiredSkills）
     * @param resume  简历解析结果（提供 skills）
     * @return 每个 JD 技能对应一条匹配结果，顺序与 JD 一致
     */
    public List<SkillMatch> match(JdParsed jd, ResumeParsed resume) {

        // 简历技能建索引：规范化名 → 证据句
        Map<String, String> resumeSkills = new HashMap<>();
        for (ResumeParsed.ResumeSkill s : resume.skills()) {
            resumeSkills.put(normalize(s.name()), s.evidence());
        }

        List<SkillMatch> out = new ArrayList<>();

        for (SkillItem required : jd.requiredSkills()) {
            String name = required.name();
            String key = normalize(name);

            // ① 精确匹配
            if (resumeSkills.containsKey(key)) {
                out.add(new SkillMatch(name, required.importance(),
                        1.0, "EXACT", resumeSkills.get(key)));
                continue;
            }

            // ② 别名匹配：简历里可能写了这个技能的其他写法
            String aliasEvidence = findByAliases(resumeSkills, name);
            if (aliasEvidence != null) {
                out.add(new SkillMatch(name, required.importance(),
                        1.0, "ALIAS", aliasEvidence));
                continue;
            }

            // ③ 字面相似：简历技能名包含 JD 技能名，或反过来
            String lexicalEvidence = findByLexical(resumeSkills, key);
            if (lexicalEvidence != null) {
                out.add(new SkillMatch(name, required.importance(),
                        0.5, "LEXICAL", lexicalEvidence));
                continue;
            }

            // ④ 缺失
            out.add(new SkillMatch(name, required.importance(), 0.0, "MISSING", null));
        }

        return out;
    }

    /**
     * 别名匹配：把 JD 技能的所有别名，拿到简历技能索引里试一遍。
     *
     * 例：JD 要 "Kubernetes"
     *     它的别名有 k8s / kube / Kubernetes
     *     简历技能索引里有 "k8s" → 命中
     */
    private String findByAliases(Map<String, String> resumeSkills, String canonical) {
        for (String alias : dictionary.aliasesOf(canonical)) {
            String evidence = resumeSkills.get(normalize(alias));
            if (evidence != null) return evidence;
        }
        return null;
    }

    /**
     * 字面相似：一方包含另一方。
     *
     * ★ 为什么要限制长度差？
     *   "Java" 包含于 "JavaScript" —— 这不该算命中。
     *   所以要求短的那个至少占长的一半长度。
     *
     *   例：
     *      "spring cloud" ⊂ "spring cloud alibaba"
     *      长度 12 vs 20，12/20 = 0.6 > 0.5 → 算部分命中 ✓
     *
     *      "java" ⊂ "javascript"
     *      长度 4 vs 10，4/10 = 0.4 < 0.5 → 不算 ✓
     *
     * ★ 注意：简历解析已经做了词边界判断，
     *   所以 "JavaScript" 不会以 "Java" 的身份进入简历技能列表。
     *   这里再加一层保护，是为了防止 JD 侧出现奇怪的组合。
     */
    private String findByLexical(Map<String, String> resumeSkills, String jdKey) {
        if (jdKey.length() < 3) return null;

        for (Map.Entry<String, String> e : resumeSkills.entrySet()) {
            String resumeKey = e.getKey();
            if (resumeKey.length() < 3) continue;

            boolean contains = resumeKey.contains(jdKey) || jdKey.contains(resumeKey);
            if (!contains) continue;

            int shorter = Math.min(resumeKey.length(), jdKey.length());
            int longer = Math.max(resumeKey.length(), jdKey.length());

            // 短的那个至少占一半长度，才算"相关"
            if (shorter * 2 >= longer) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 规范化：小写 + 把空格/短横/点统一成单个空格。
     *
     * 这样 "Spring Boot" / "springboot" / "spring-boot" 都能对上
     *（配合词典里的别名表）。
     */
    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-.]+", " ")
                .trim();
    }
}
