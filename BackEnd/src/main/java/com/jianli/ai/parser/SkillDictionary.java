package com.jianli.ai.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  技能词典。从 dict/skills.json 加载"技能 → 别名列表"的映射，
 *  然后在文本里找出所有出现的技能。
 *
 *  ★ 它是整个"无 AI 也能跑"方案的地基：
 *    JD 解析、简历解析、关键词匹配、字面相似度降级，
 *    四个地方共用这一份数据。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么用词典而不是硬编码正则？】
 *
 *  反例：写成一行大正则
 *      /(?i)(java|spring|mysql|redis|kafka|docker|k8s|...)/g
 *    问题：
 *      1. 加一个技能就要改代码、重新编译
 *      2. 正则越来越长，难以维护
 *      3. 别名写在一起看不清谁是谁的别名
 *
 *  用 JSON 词典：
 *      加技能只改 JSON，不改代码
 *      结构清晰：canonical（规范名） + aliases（别名）
 *      一份数据服务四个模块
 * ═══════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillDictionary {

    private final ObjectMapper objectMapper;

    /** 原始条目，按加载顺序 */
    private List<Entry> entries = List.of();

    /**
     * 别名 → 条目的索引。
     *
     * ★ 为什么用 LinkedHashMap？
     *   保持插入顺序，让 findAll 的结果稳定（便于测试和调试）。
     *   HashMap 的顺序是不确定的。
     *
     * ★ key 统一转小写，查找时也转小写 —— 实现大小写不敏感。
     */
    private final Map<String, Entry> aliasIndex = new LinkedHashMap<>();

    @Getter
    public static class Entry {
        private String canonical;
        private List<String> aliases = List.of();
    }

    /** 匹配结果：规范名 + 命中的别名 + 在文本中的位置 */
    public record Match(String canonical, String matchedAlias, int start) {}

    /**
     * 启动时加载词典。
     *
     * ★ @PostConstruct 在 Bean 创建完成后自动执行，
     *   保证其他类注入 SkillDictionary 时它已经加载好了。
     *
     * ★ 加载失败怎么办？
     *   【不抛异常】，只打日志、置空。
     *   理由：词典是"能力增强"，不是"系统必需"。
     *   词典挂了系统还能起、还能登录、还能管理简历 ——
     *   只是解析不出技能。这比"整个应用起不来"好得多。
     */
    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("dict/skills.json").getInputStream()) {
            entries = objectMapper.readValue(in, new TypeReference<List<Entry>>() {});
        } catch (Exception e) {
            log.error("技能词典加载失败，解析能力受限", e);
            entries = List.of();
        }

        for (Entry e : entries) {
            if (e.getCanonical() == null) continue;
            // 规范名本身也是一个别名（这样直接写 "Java" 也能命中）
            aliasIndex.put(e.getCanonical().toLowerCase(Locale.ROOT), e);
            for (String a : e.getAliases()) {
                if (a != null && !a.isBlank()) {
                    aliasIndex.put(a.toLowerCase(Locale.ROOT), e);
                }
            }
        }

        log.info("技能词典加载完成：{} 个技能，{} 个可匹配别名", entries.size(), aliasIndex.size());
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【在文本中找出所有技能】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param text 待扫描的文本（JD 或简历）
     * @return 去重后的匹配列表，按出现位置排序
     *
     * 【★ 为什么要词边界判断？】
     *
     *   反例：
     *      技能 "Java" 用 indexOf 在 "JavaScript" 里能找到 ——
     *      于是简历写了 JavaScript，系统认为他"会 Java"。这是错的。
     *
     *      技能 "Go" 在 "algorithm" 里也能找到。
     *
     *   ★ 所以对【纯 ASCII 的别名】要求两侧不是字母数字：
     *      "Java " 命中，"JavaScript" 不命中
     *      " Go " 命中，"algorithm" 不命中
     *
     *   ★ 对【中文别名】不做边界判断 ——
     *      中文没有空格分词，"微服务架构"里出现"微服务"应该算命中。
     */
    public List<Match> findAll(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String lower = text.toLowerCase(Locale.ROOT);
        List<Match> raw = new ArrayList<>();

        for (Map.Entry<String, Entry> e : aliasIndex.entrySet()) {
            String alias = e.getKey();

            int from = 0;
            while (true) {
                int idx = lower.indexOf(alias, from);
                if (idx < 0) break;

                if (isBoundaryOk(lower, idx, alias.length())) {
                    raw.add(new Match(e.getValue().getCanonical(), alias, idx));
                }
                from = idx + alias.length();
            }
        }

        /*
         * 【去重】同一个技能只保留第一次出现。
         *
         * ★ 为什么需要？
         *   一个技能可能有多个别名都命中（比如同时写了 "k8s" 和 "Kubernetes"），
         *   或者同一个词在文本里出现多次。
         *   评分只关心"有没有"，重复计数会让覆盖率算错。
         */
        Map<String, Match> unique = new LinkedHashMap<>();
        raw.stream()
                .sorted(Comparator.comparingInt(Match::start))
                .forEach(m -> unique.putIfAbsent(m.canonical(), m));

        return new ArrayList<>(unique.values());
    }

    /**
     * 判断匹配位置两侧是否是"词边界"。
     *
     * ★ 只对纯 ASCII 别名做检查（中文不做）。
     *   判断方式：前后一个字符不能是字母或数字。
     */
    private boolean isBoundaryOk(String text, int start, int len) {
        String alias = text.substring(start, start + len);

        // 含非 ASCII 字符（中文等）→ 不做边界检查
        boolean isAscii = alias.chars().allMatch(c -> c < 128);
        if (!isAscii) return true;

        char before = start > 0 ? text.charAt(start - 1) : ' ';
        int afterIdx = start + len;
        char after = afterIdx < text.length() ? text.charAt(afterIdx) : ' ';

        return !Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after);
    }

    /** 词典里有多少个技能 */
    public int size() {
        return entries.size();
    }

    /**
     * 查一个技能的规范名对应的所有别名（含规范名本身）。
     *
     * ★ 用在哪：关键词密度统计时，"k8s" 出现的次数也要算到 "Kubernetes" 头上。
     */
    public List<String> aliasesOf(String canonical) {
        return entries.stream()
                .filter(e -> e.getCanonical() != null && e.getCanonical().equalsIgnoreCase(canonical))
                .findFirst()
                .map(e -> {
                    List<String> all = new ArrayList<>(e.getAliases());
                    all.add(e.getCanonical());
                    return all;
                })
                .orElse(List.of(canonical));
    }
}
