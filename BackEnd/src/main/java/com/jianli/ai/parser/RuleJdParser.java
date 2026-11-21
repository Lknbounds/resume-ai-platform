package com.jianli.ai.parser;

import com.jianli.ai.model.JdParsed;
import com.jianli.ai.model.SkillItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  JD 规则解析器。从一段 JD 文本里抽出：
 *      硬技能（带重要度）、加分项、最低经验年限、学历要求、关键词
 *
 *  ★ 完全离线、不花钱、毫秒级。
 *    它的存在让"AI 关闭时系统仍可用"这件事成立。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 重要度是怎么判的？】
 *
 *  在技能出现的【那一行】里找修饰词：
 *      必须 / 精通 / 熟练掌握 / 熟练使用 / 扎实   → HIGH
 *      熟悉 / 掌握 / 具备 / 经验                  → MEDIUM
 *      优先 / 加分 / 了解 / 者优先                → LOW
 *
 *  同一行有多个修饰词时取最高（先判 HIGH 再判 LOW）。
 *  找不到修饰词 → MEDIUM（默认值）。
 *
 *  ★ 这不是完美的 NLP，但它【可解释】：
 *    用户问"为什么 Redis 是 MEDIUM"，你能指着那一行说
 *    "因为写的是'熟悉 Redis'"。AI 做不到这种确定性。
 */
@Component
@RequiredArgsConstructor
public class RuleJdParser {

    /** 3-5 年 / 3~5年 / 3至5年 */
    private static final Pattern YEARS_RANGE =
            Pattern.compile("(\\d{1,2})\\s*[-~至到]\\s*(\\d{1,2})\\s*年");

    /** 3 年以上 / 3年及以上 */
    private static final Pattern YEARS_MIN =
            Pattern.compile("(\\d{1,2})\\s*年(?:及)?以上");

    private static final List<String> HIGH_WORDS =
            List.of("必须", "精通", "熟练掌握", "熟练使用", "扎实", "深入理解");
    private static final List<String> MEDIUM_WORDS =
            List.of("熟悉", "掌握", "具备", "经验");
    private static final List<String> LOW_WORDS =
            List.of("优先", "加分", "了解", "者优先");

    /**
     * 技能分类表。
     *
     * ★ 只是为了前端展示时能分组（语言/框架/数据库/中间件/工具）。
     *   不参与评分 —— 评分只看重要度。
     *
     * ★ 表里没有的技能统一归 OTHER。加新技能不需要改这里。
     */
    private static final Map<String, String> CATEGORY = Map.ofEntries(
            Map.entry("Java", "LANGUAGE"), Map.entry("Python", "LANGUAGE"),
            Map.entry("Go", "LANGUAGE"), Map.entry("JavaScript", "LANGUAGE"),
            Map.entry("TypeScript", "LANGUAGE"),
            Map.entry("Spring Boot", "FRAMEWORK"), Map.entry("Spring Cloud", "FRAMEWORK"),
            Map.entry("Vue", "FRAMEWORK"), Map.entry("React", "FRAMEWORK"),
            Map.entry("MyBatis", "FRAMEWORK"),
            Map.entry("MySQL", "DATABASE"), Map.entry("PostgreSQL", "DATABASE"),
            Map.entry("MongoDB", "DATABASE"),
            Map.entry("Redis", "MIDDLEWARE"), Map.entry("Kafka", "MIDDLEWARE"),
            Map.entry("RocketMQ", "MIDDLEWARE"), Map.entry("RabbitMQ", "MIDDLEWARE"),
            Map.entry("Elasticsearch", "MIDDLEWARE"),
            Map.entry("Docker", "TOOL"), Map.entry("Kubernetes", "TOOL"),
            Map.entry("Linux", "TOOL"), Map.entry("Nginx", "TOOL"),
            Map.entry("Git", "TOOL"), Map.entry("Maven", "TOOL"),
            Map.entry("分布式事务", "DOMAIN"), Map.entry("高并发", "DOMAIN"),
            Map.entry("微服务", "DOMAIN"), Map.entry("JVM调优", "DOMAIN"),
            Map.entry("多线程", "DOMAIN"), Map.entry("缓存", "DOMAIN"),
            Map.entry("团队协作", "SOFT"), Map.entry("跨部门沟通", "SOFT")
    );

    /**
     * ★★ 软技能黑名单 —— 不参与评分，只做建议提示
     *
     * 【为什么？这是"测不准就不要测"原则】
     *
     *  JD 里常写"具备良好的团队协作能力"。
     *  如果把它当成硬技能参与覆盖率计算：
     *      简历里没写"团队协作"这四个字 → 判定缺失 → 扣分
     *
     *  但这是错的：
     *    1. 简历能体现团队协作，只是靠"带过 5 人团队""跨 3 个部门推进"这类
     *       成果描述，不是靠写"我善于合作"
     *    2. ATS 系统本身只看关键词匹配，不评估软技能
     *    3. 用户会觉得"系统在挑我毛病"，而这是个无法证明的指控
     *
     *  ★ 所以：软技能从 requiredSkills 里剔除，
     *    单独放进 softSkills 字段，只用来生成"建议"（不扣分）。
     */
    private static final java.util.Set<String> SOFT_SKILLS = java.util.Set.of(
            "团队协作", "跨部门沟通", "沟通能力", "学习能力", "抗压能力"
    );

    private final SkillDictionary dictionary;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【主入口】
     * ═══════════════════════════════════════════════════════════════
     */
    public JdParsed parse(String jdText) {
        String text = jdText == null ? "" : jdText;
        String[] lines = text.split("\n");

        /*
         * ═══════════════════════════════════════════════════════════
         *  ★★ 关键修复：一个技能可能在多行出现，要取【最高的重要度】
         * ═══════════════════════════════════════════════════════════
         *
         * 踩过的坑：
         *   JD 标题是「Java 后端开发工程师」，
         *   要求里是「3 年以上 Java 开发经验，熟练掌握 Spring Boot、MySQL」。
         *
         *   如果只看【第一次出现】—— 也就是标题行，
         *   那一行没有"熟练掌握"，Java 就被判成 MEDIUM。
         *   但事实上 JD 明确要求"熟练掌握 Java"，应该是 HIGH。
         *
         * 修复：收集每个技能在所有出现行里的重要度，取最高。
         *
         * ★ 这是"跑一遍才发现"的 bug —— 光看代码很难想到。
         */
        Map<String, SkillItem> skillBest = new LinkedHashMap<>();
        Map<String, Boolean> skillIsNice = new LinkedHashMap<>();

        for (SkillDictionary.Match m : dictionary.findAll(text)) {
            String canonical = m.canonical();

            /*
             * ★ 为什么用 findAll 只拿到第一次出现？
             *   词典的 findAll 已经按 canonical 去重了（只保留首个位置）。
             *   所以这里需要【自己再扫一遍所有行】找这个技能的其他出现。
             *
             *   更彻底的做法是让 findAll 返回所有出现位置，
             *   但那会影响简历解析（那里只需要一条证据）。
             *   折中：这里按"技能名在哪些行出现"重新判断。
             */
            String importance = bestImportanceAcrossLines(lines, canonical);
            String category = CATEGORY.getOrDefault(canonical, "OTHER");

            boolean nice = SkillItem.LOW.equals(importance);
            Boolean prevNice = skillIsNice.get(canonical);
            if (prevNice != null) {
                // 已经判过：如果上次不是加分项、这次也不是 → 保持；有任一不是 nice 就不算 nice
                nice = prevNice && nice;
            }
            skillIsNice.put(canonical, nice);

            SkillItem prev = skillBest.get(canonical);
            if (prev == null || rank(importance) > rank(prev.importance())) {
                skillBest.put(canonical, new SkillItem(canonical, importance, category));
            }
        }

        List<SkillItem> required = new ArrayList<>();
        List<SkillItem> nice = new ArrayList<>();
        List<String> softSkills = new ArrayList<>();

        for (SkillItem item : skillBest.values()) {
            /*
             * ★ 软技能单独拎出来：不参与评分，只做建议。
             *   见 SOFT_SKILLS 的说明。
             */
            if (SOFT_SKILLS.contains(item.name())) {
                softSkills.add(item.name());
                continue;
            }

            if (Boolean.TRUE.equals(skillIsNice.get(item.name()))) {
                nice.add(new SkillItem(item.name(), SkillItem.LOW, item.category()));
            } else {
                required.add(item);
            }
        }

        JdParsed parsed = new JdParsed(
                guessTitle(lines),
                required,
                nice,
                guessYears(text),
                guessEducation(text),
                guessKeywords(text)
        );

        // ★ 软技能通过单独的字段带出去（JdParsed 里加）
        return parsed.withSoftSkills(softSkills);
    }

    /**
     * 在【所有含该技能的行的】里，取最高的重要度。
     *
     * ★ 为什么是"所有行"而不是"第一次出现的行"？
     *   见 parse 方法里的说明 —— 标题行往往没有修饰词。
     *
     * ★ 为什么要判断"行里含该技能"？
     *   因为同一个词可能在标题行出现（"Java 后端开发工程师"），
     *   也可能在要求行出现（"熟练掌握 Java"）。
     *   只有真正含这个词的行，才该用来判断它的重要度。
     */
    private String bestImportanceAcrossLines(String[] lines, String canonical) {
        /*
         * ★★ 这里曾经有个 bug：初始值设成 MEDIUM，
         *   导致 rank(LOW)=1 永远不大于 rank(MEDIUM)=2 ——
         *   于是所有"优先/了解"类的技能都被判成 MEDIUM，
         *   加分项列表永远是空的。
         *
         * 修复：初始值设成 null，第一次遇到时直接采用，之后才比较。
         */
        String best = null;

        for (String line : lines) {
            // 这一行提到了这个技能吗？
            boolean mentioned = dictionary.findAll(line).stream()
                    .anyMatch(m -> m.canonical().equalsIgnoreCase(canonical));
            if (!mentioned) continue;

            String imp = judgeImportance(line);
            if (best == null || rank(imp) > rank(best)) {
                best = imp;
            }
        }

        return best == null ? SkillItem.MEDIUM : best;
    }

    /** 重要度的排序值，用于比较高低 */
    private int rank(String importance) {
        return switch (importance == null ? "MEDIUM" : importance) {
            case "HIGH" -> 3;
            case "LOW" -> 1;
            default -> 2;
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  各字段的抽取规则
    // ═══════════════════════════════════════════════════════════════

    /**
     * 猜岗位名称：找第一个包含"工程师/开发/架构师"等词的短行。
     *
     * ★ 为什么要去掉括号内容？
     *   "Java 后端开发工程师（3-5 年）" → "Java 后端开发工程师"
     *   括号里通常是年限、地点，不是岗位名本身。
     */
    private String guessTitle(String[] lines) {
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.length() > 40) continue;
            if (t.matches(".*(工程师|开发|架构师|专家|经理|分析师|实习生|设计师).*")) {
                return t.replaceAll("[（(].*?[)）]", "").trim();
            }
        }
        return null;
    }

    /** 经验年限：优先取区间左端（3-5 年 → 3），否则取"X 年以上" */
    private int guessYears(String text) {
        Matcher range = YEARS_RANGE.matcher(text);
        if (range.find()) {
            return Integer.parseInt(range.group(1));
        }
        Matcher min = YEARS_MIN.matcher(text);
        if (min.find()) {
            return Integer.parseInt(min.group(1));
        }
        return 0;
    }

    /** 学历：从高往低判，第一个命中的就是要求 */
    private String guessEducation(String text) {
        if (text.contains("博士")) return "PHD";
        if (text.contains("硕士") || text.contains("研究生")) return "MASTER";
        if (text.contains("本科")) return "BACHELOR";
        if (text.contains("大专") || text.contains("专科")) return "ASSOCIATE";
        return "NONE";
    }

    /** 关键词：从固定词表里找，命中就收集 */
    private List<String> guessKeywords(String text) {
        List<String> kw = new ArrayList<>();
        for (String w : List.of("高并发", "微服务", "分布式", "性能优化",
                "架构设计", "团队协作", "代码 review", "CI/CD")) {
            if (text.contains(w)) kw.add(w);
        }
        return kw;
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据字符偏移量找到它所在的行。
     *
     * ★ 为什么要这个？
     *   要判断"技能所在行的修饰词"（"熟悉 Redis"里的"熟悉"）。
     *   词典只给出技能的起始位置，需要换算成"哪一行"。
     *
     * ★ 累加每行长度 + 1（换行符）来推进偏移量。
     */
    private String lineAt(String[] lines, int offset) {
        int acc = 0;
        for (String line : lines) {
            acc += line.length() + 1;
            if (offset < acc) return line;
        }
        return "";
    }

    /**
     * 判断重要度。
     *
     * 【★★ 判定顺序：HIGH → MEDIUM → LOW】
     *
     * 这个顺序是"跑一遍之后"才定下来的，一开始写的是 HIGH → LOW → MEDIUM，
     * 结果出了问题。看这个真实例子：
     *
     *      "熟悉 Redis、Kafka 等中间件，有高并发经验者优先"
     *                    ↑ Redis 是要求              ↑ 只有"高并发"是加分项
     *
     * 如果先判 LOW（因为有"优先"），整行的 Redis/Kafka 都会变成加分项，
     * 于是技能覆盖率的分母少了它们 —— 用户明明会 Redis 却不算分。
     *
     * ★ 改成先判 MEDIUM 之后：
     *     "熟悉 Redis..."      → 含"熟悉" → MEDIUM → 算必需技能 ✓
     *     "了解 Kubernetes 者优先" → 不含 MEDIUM 词 → LOW → 算加分项 ✓
     *     "熟练掌握 Java"      → 含"熟练" → HIGH ✓
     *
     * ⚠️ 仍然不完美：一行里如果有"熟悉 A，了解 B 者优先"，
     *    B 会被判成 MEDIUM。规则版做不到"精确到短语"，
     *    这是它的能力边界 —— AI 版能做到，这就是 AI 的价值。
     */
    private String judgeImportance(String line) {
        if (HIGH_WORDS.stream().anyMatch(line::contains)) return SkillItem.HIGH;
        if (MEDIUM_WORDS.stream().anyMatch(line::contains)) return SkillItem.MEDIUM;
        if (LOW_WORDS.stream().anyMatch(line::contains)) return SkillItem.LOW;
        return SkillItem.MEDIUM;
    }
}
