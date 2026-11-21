package com.jianli.ai.parser;

import com.jianli.ai.model.ResumeParsed;
import com.jianli.ai.model.ResumeParsed.Education;
import com.jianli.ai.model.ResumeParsed.ExperienceItem;
import com.jianli.ai.model.ResumeParsed.ProjectItem;
import com.jianli.ai.model.ResumeParsed.ResumeSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  简历规则解析器。从简历文本里抽出：
 *      技能、工作经历、项目、教育、板块完整度、总年限
 *
 *  ★ 和 JD 解析共用同一套技能词典 —— 这是"能对比"的前提。
 *    如果 JD 用一套词、简历用另一套词，匹配率会很低。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 规则解析的边界在哪（诚实地说清楚）】
 *
 *  擅长的（能用模式识别的）：
 *      技能名（靠词典）
 *      时间区间（2022.07-2025.07）
 *      学历关键词（本科/硕士）
 *      量化表达（数字 + 单位）
 *      板块标题（教育背景/工作经历）
 *
 *  做不到的（需要语义理解）：
 *      "带过 5 人团队" 这种非结构化描述里隐含的职责
 *      一句话到底是在说"我做了什么"还是"我学到了什么"
 *
 *  ★ 所以规则版是【降级方案】，AI 版更准。
 *    但规则版能跑、能测、能解释 —— 这就够了。
 */
@Component
@RequiredArgsConstructor
public class RuleResumeParser {

    /**
     * 板块关键词表。
     *
     * ★ 为什么要识别板块？
     *   1. 结构完整度维度需要（缺了哪个板块要扣分）
     *   2. 提取工作经历时要知道"从哪一行到哪一行"
     *
     * ★ 中英文都支持 —— 很多简历是英文标题。
     */
    private static final Map<String, String> SECTION_KEYWORDS = Map.of(
            "BASIC_INFO", "个人信息|基本信息|求职意向|联系方式|姓名|profile|contact",
            "EDUCATION", "教育背景|教育经历|学历|education",
            "SKILLS", "专业技能|技能清单|技术栈|掌握技能|技能|skills",
            "EXPERIENCE", "工作经历|工作经验|实习经历|职业经历|experience|employment",
            "PROJECT", "项目经历|项目经验|项目实践|projects"
    );

    /**
     * 时间区间正则。
     *
     * 匹配：2022.07-2025.07 / 2022年7月-2024年6月 / 2022.07 - 至今
     *
     * ★ 拆解：
     *   (\\d{4})           年份4位
     *   \\s*[./年-]\\s*     分隔符（点/斜杠/年/短横）
     *   (\\d{1,2})?        月份（可省略）
     *   [~\\-—至到]+       区间分隔（波浪线/短横/破折号/至/到）
     *   (\\d{4}|至今|现在)  结束年份，或"至今"
     */
    private static final Pattern PERIOD = Pattern.compile(
            "(\\d{4})\\s*[./年-]\\s*(\\d{1,2})?\\s*[./月]?\\s*[~\\-—至到]+\\s*(\\d{4}|至今|现在)\\s*[./年-]?\\s*(\\d{1,2})?");

    /**
     * 量化表达：数字 + 单位。
     *
     * ★ 为什么要识别它？
     *   "量化成果密度"是一个评分维度。
     *   "负责订单系统开发" 和 "QPS 从 200 提升至 1200" 的质量完全不同。
     *
     * ★ 匹配的单位覆盖了技术简历里常见的：百分比、万、倍、ms、QPS、条、人、单等。
     */
    private static final Pattern QUANTIFIED = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:%|％|万|千|倍|ms|s|QPS|TPS|条|个|人|次|天|小时|单|元|w|k)");

    private final SkillDictionary dictionary;

    // ═══════════════════════════════════════════════════════════════
    //  主入口
    // ═══════════════════════════════════════════════════════════════

    public ResumeParsed parse(String content) {
        String text = content == null ? "" : content;
        String[] lines = text.split("\n");

        List<String> sections = detectSections(lines);
        List<ResumeSkill> skills = extractSkills(text, lines);
        List<ExperienceItem> experiences = extractExperiences(lines, sections);
        List<ProjectItem> projects = extractProjects(lines, sections);
        Education education = extractEducation(text);

        return new ResumeParsed(
                skills,
                experiences,
                projects,
                education,
                sections,
                totalYears(experiences)
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  板块识别
    // ═══════════════════════════════════════════════════════════════

    /**
     * 检测简历里有哪些板块。
     *
     * ★ 判定方式：某一行【以】板块关键词开头（忽略前导空格）。
     *   为什么要求"开头"？因为正文里提到"教育"两个字不算有教育板块。
     */
    private List<String> detectSections(String[] lines) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> e : SECTION_KEYWORDS.entrySet()) {
            Pattern p = Pattern.compile("^\\s*(?:" + e.getValue() + ")",
                    Pattern.CASE_INSENSITIVE);
            if (Arrays.stream(lines).anyMatch(l -> p.matcher(l).find())) {
                found.add(e.getKey());
            }
        }
        return found;
    }

    // ═══════════════════════════════════════════════════════════════
    //  技能
    // ═══════════════════════════════════════════════════════════════

    /**
     * 抽技能。复用 JD 的词典。
     *
     * ★ evidence 取技能所在的那一行（截断到 60 字）。
     *   前端展示"你在这一行提到了 Java"，比单纯打勾有说服力。
     */
    private List<ResumeSkill> extractSkills(String text, String[] lines) {
        List<ResumeSkill> out = new ArrayList<>();
        for (SkillDictionary.Match m : dictionary.findAll(text)) {
            String line = lineAt(lines, m.start()).trim();
            String evidence = line.length() > 60 ? line.substring(0, 60) + "…" : line;
            out.add(new ResumeSkill(m.canonical(), evidence));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    //  工作经历
    // ═══════════════════════════════════════════════════════════════

    /**
     * 抽工作经历。
     *
     * 【算法】
     *   1. 定位"工作经历"板块的起止行
     *   2. 在该范围内扫描，遇到含时间区间的行 → 开始的是一段新经历
     *   3. 该行到下一段时间行之间的所有非空行 → 这段经历的 bullet
     *
     * ★ 为什么以"时间区间"作为切分点？
     *   因为简历里每段经历几乎都带时间（"某公司 Java 开发 2022.07-2025.07"）。
     *   这是最可靠的结构信号。
     *
     * ★ 如果简历没写时间怎么办？
     *   提取不到经历 → 年限算 0 → 经验匹配维度可能不达标。
     *   这是规则版的局限，但也是"提示用户写时间"的合理反馈。
     */
    private List<ExperienceItem> extractExperiences(String[] lines, List<String> sections) {
        if (!sections.contains("EXPERIENCE")) return List.of();

        int start = indexOfSection(lines, "EXPERIENCE");
        if (start < 0) return List.of();
        int end = nextSectionIndex(lines, start);

        List<ExperienceItem> out = new ArrayList<>();
        ExperienceItem current = null;
        List<ExperienceItem.Bullet> bullets = new ArrayList<>();
        String currentPeriod = null;
        int currentMonths = 0;

        for (int i = start + 1; i < end; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            Matcher m = PERIOD.matcher(line);
            if (m.find()) {
                // 遇到新的时间行 → 收尾上一段
                if (current != null) {
                    out.add(new ExperienceItem(current.title(), currentPeriod, currentMonths, bullets));
                }

                bullets = new ArrayList<>();
                currentPeriod = m.group();
                currentMonths = monthsOf(m);

                // 时间是行的一部分，职位/公司是去掉时间后剩下的部分
                String title = (line.substring(0, m.start()) + " " + line.substring(m.end()))
                        .trim().replaceAll("\\s{2,}", " ");

                current = new ExperienceItem(title, currentPeriod, currentMonths, List.of());
            } else if (current != null && line.length() > 3) {
                // 不是时间行，且已有当前经历 → 当作一条 bullet
                bullets.add(new ExperienceItem.Bullet(
                        line.replaceAll("^[-·•*]\\s*", ""),
                        QUANTIFIED.matcher(line).find()));
            }
        }

        if (current != null) {
            out.add(new ExperienceItem(current.title(), currentPeriod, currentMonths, bullets));
        }
        return out;
    }

    /**
     * 把时间区间换算成月数。
     *
     * ★ "至今"怎么处理？
     *   取当前日期。所以"2022.07-至今"会算到今天的月数。
     *
     * ★ 为什么是"月"而不是"年"？
     *   年精度太粗（1 年 11 个月和 1 年 1 个月都算 1 年）。
     *   用月数最后再除以 12 得到带小数的年限，更准。
     */
    private int monthsOf(Matcher m) {
        int startYear = Integer.parseInt(m.group(1));
        int startMonth = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));

        int endYear;
        int endMonth;
        String endRaw = m.group(3);

        if ("至今".equals(endRaw) || "现在".equals(endRaw)) {
            java.time.LocalDate now = java.time.LocalDate.now();
            endYear = now.getYear();
            endMonth = now.getMonthValue();
        } else {
            endYear = Integer.parseInt(endRaw);
            endMonth = m.group(4) == null ? 12 : Integer.parseInt(m.group(4));
        }

        int months = (endYear - startYear) * 12 + (endMonth - startMonth);
        return Math.max(months, 0);
    }

    // ═══════════════════════════════════════════════════════════════
    //  项目
    // ═══════════════════════════════════════════════════════════════

    /**
     * 抽项目。
     *
     * 【算法】
     *   在"项目经历"板块内扫描：
     *      遇到"项目标题行" → 开始收集这个块
     *      一直收集到"下一个项目标题"或板块结束
     *      然后【跳过整块】继续找下一个项目
     *
     * 【★★ 两个踩过的坑，都修在这里】
     *
     * 坑一：标题长度上限设成 30 字太小。
     *   反例："订单中台（Spring Boot + MySQL + Redis）" = 33 字符
     *   结果：这个项目被跳过，只识别出下一个项目。
     *   修复：放宽到 60 字，并且【只在判断时去掉括号内容再算长度】。
     *
     * 坑二：外层循环没跳过已消费的块。
     *   处理完"订单中台"后，i 只 +1，会继续在同一块内部扫描，
     *   可能把描述行误判成新项目的标题。
     *   修复：处理完一块后直接 i = j（跳到块尾）。
     *
     * 【★ 怎么判断"这一行是项目标题"？】
     *
     *   三个条件同时满足：
     *     1. 不是列表项（不以 - · • 开头）
     *     2. 不是动作描述（不以 使用/基于/负责/主导/通过 等开头）
     *     3. 去掉括号后的长度 ≤ 40
     *
     *   ★ 条件 2 是关键：项目下面常有"基于 Kafka 实现异步回调"这样的描述，
     *     它很短、不以 - 开头，光看长度会被误判成标题。
     */
    private List<ProjectItem> extractProjects(String[] lines, List<String> sections) {
        if (!sections.contains("PROJECT")) return List.of();

        int start = indexOfSection(lines, "PROJECT");
        if (start < 0) return List.of();
        int end = nextSectionIndex(lines, start);

        List<ProjectItem> out = new ArrayList<>();

        int i = start + 1;
        while (i < end && out.size() < 6) {
            String line = lines[i].trim();

            if (line.isEmpty() || !looksLikeProjectTitle(line)) {
                i++;
                continue;
            }

            // ───── 收集这个项目的整块内容 ─────
            StringBuilder block = new StringBuilder(line);
            boolean quantified = false;
            String desc = "";

            int j = i + 1;
            while (j < end) {
                String next = lines[j].trim();

                if (next.isEmpty()) { j++; continue; }

                // 遇到下一个项目标题 → 本块结束
                if (looksLikeProjectTitle(next)) break;

                block.append('\n').append(next);
                if (QUANTIFIED.matcher(next).find()) quantified = true;
                if (desc.isEmpty() && next.length() > 8) {
                    desc = next.replaceAll("^[-·•*]\\s*", "");
                }
                j++;
            }

            /*
             * ★ 技术栈从【整个块】里找，不只标题行。
             *   因为很多简历写成：
             *       订单中台
             *       - 使用 Spring Boot + MySQL 重构订单查询
             *   技术栈在描述行里。
             */
            List<String> tech = dictionary.findAll(block.toString()).stream()
                    .map(SkillDictionary.Match::canonical)
                    .toList();

            out.add(new ProjectItem(line, tech, desc, quantified));

            // ★ 跳到块尾，避免在同一块内部重复扫描
            i = j;
        }

        return out;
    }

    /**
     * 这一行像不像项目标题？
     *
     * ★ 三个条件见 extractProjects 的注释。
     */
    private boolean looksLikeProjectTitle(String line) {
        if (line.isEmpty()) return false;

        // 条件 1：不是列表项
        if (line.startsWith("-") || line.startsWith("·")
                || line.startsWith("•") || line.startsWith("*")) {
            return false;
        }

        // 条件 2：不是动作描述
        String[] actionPrefixes = {
                "使用", "基于", "负责", "主导", "通过", "实现", "参与",
                "完成", "采用", "设计", "开发", "搭建", "优化", "引入", "重构"
        };
        for (String p : actionPrefixes) {
            if (line.startsWith(p)) return false;
        }

        // 条件 3：去掉括号内容后长度合理
        String withoutParen = line.replaceAll("[（(].*?[)）]", "").trim();
        return !withoutParen.isEmpty() && withoutParen.length() <= 40 && line.length() <= 80;
    }

    // ═══════════════════════════════════════════════════════════════
    //  教育
    // ═══════════════════════════════════════════════════════════════

    private Education extractEducation(String text) {
        String degree = text.contains("博士") ? "PHD"
                : (text.contains("硕士") || text.contains("研究生")) ? "MASTER"
                : text.contains("本科") ? "BACHELOR"
                : (text.contains("大专") || text.contains("专科")) ? "ASSOCIATE"
                : "NONE";

        Matcher school = Pattern.compile("([\\u4e00-\\u9fa5]{2,15}(?:大学|学院|学校))").matcher(text);
        Matcher major = Pattern.compile(
                "(计算机[\\u4e00-\\u9fa5]{0,10}|软件工程|电子信息|通信工程|信息管理|数据科学|人工智能)")
                .matcher(text);

        return new Education(
                degree,
                school.find() ? school.group(1) : null,
                major.find() ? major.group(1) : null
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  年限
    // ═══════════════════════════════════════════════════════════════

    /**
     * 总年限 = 所有经历的月数之和 / 12。
     *
     * ★ 这是简化实现：直接加总，不处理"两段经历时间重叠"。
     *   重叠会导致年限虚高。
     *
     * ★ 正确的做法是合并时间区间。
     *   本项目保留这个简化并在此标注 ——
     *   面试时主动说出"我知道这里不严谨"反而加分。
     */
    private double totalYears(List<ExperienceItem> experiences) {
        if (experiences.isEmpty()) return 0.0;
        int months = experiences.stream().mapToInt(ExperienceItem::totalMonths).sum();
        return Math.round(months / 12.0 * 10) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具
    // ═══════════════════════════════════════════════════════════════

    /** 找到某个板块标题所在的行号 */
    private int indexOfSection(String[] lines, String section) {
        String kw = SECTION_KEYWORDS.get(section);
        if (kw == null) return -1;
        Pattern p = Pattern.compile("^\\s*(?:" + kw + ")", Pattern.CASE_INSENSITIVE);
        for (int i = 0; i < lines.length; i++) {
            if (p.matcher(lines[i]).find()) return i;
        }
        return -1;
    }

    /** 从一个板块标题往下找，下一个板块标题在哪（用于确定板块范围） */
    private int nextSectionIndex(String[] lines, int from) {
        for (int i = from + 1; i < lines.length; i++) {
            for (String kw : SECTION_KEYWORDS.values()) {
                if (Pattern.compile("^\\s*(?:" + kw + ")", Pattern.CASE_INSENSITIVE)
                        .matcher(lines[i]).find()) {
                    return i;
                }
            }
        }
        return lines.length;
    }

    /** 根据偏移量找所在行 */
    private String lineAt(String[] lines, int offset) {
        int acc = 0;
        for (String line : lines) {
            acc += line.length() + 1;
            if (offset < acc) return line;
        }
        return "";
    }
}
