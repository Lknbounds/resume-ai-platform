package com.jianli.resume.service;

import com.jianli.resume.dto.ExtractResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  提取文本的"质量检查"。★ 这是和 ResumeIQ 最大的区别。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 它解决什么问题？】
 *
 *  ResumeIQ 的流程：
 *      上传 → extract_text() → 空字符串 → 照样分析 → 0 分
 *      → 用户以为"我简历太差"，其实是文件没读出来
 *
 *  我们的流程：
 *      上传 → 提取 → 【本类检查】→ REJECT 就明确告知"这份文件读不出来"
 *                        ↘ PASS/WARN → 给用户预览，确认后才创建
 *
 *  ★ 核心原则：宁可让用户多花 30 秒粘贴一次，
 *    也不要让他拿着一份"读错了的简历"得到一个假分数。
 *
 *  【★ 检查哪些指标？】
 *
 *  必须拒绝的（文本根本不可用）：
 *    - 有效字符 < 50        → 空白文件 / 扫描件
 *    - 乱码字符占比 > 5%    → 字体映射缺失，读出来全是 �
 *
 *  需要提醒的（能用，但要用户核对）：
 *    - 有效字符 < 200       → 内容可能提取不全（表格/分栏丢失）
 *    - 有乱码但不严重       → 部分字符丢了
 *    - 重复行过多          → 可能是页眉页脚被重复提取
 *    - 缺板块标题          → 可能格式丢了
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractQualityChecker {

    /** 低于这个字符数，认为文件没读到内容 */
    private static final int REJECT_MIN_CHARS = 50;

    /** 低于这个字符数，能用但提醒"可能不全" */
    private static final int WARN_MIN_CHARS = 200;

    /** 乱码占比超过这个，直接拒绝 */
    private static final double REJECT_BAD_CHAR_RATIO = 0.05;

    /** 乱码占比超过这个，提醒 */
    private static final double WARN_BAD_CHAR_RATIO = 0.01;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【检查】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param rawText 提取出的原始文本
     * @return 质量报告
     */
    public ExtractResultVO.Quality check(String rawText) {
        List<String> rejectIssues = new ArrayList<>();
        List<String> warnIssues = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            return new ExtractResultVO.Quality(
                    "REJECT",
                    "这个文件里没有读到任何文字",
                    List.of("可能是扫描件（图片型 PDF），或者是空文件")
            );
        }

        // ─────────── ① 有效字符数 ───────────
        int charCount = rawText.replaceAll("\\s", "").length();

        if (charCount < REJECT_MIN_CHARS) {
            rejectIssues.add("只读到 %d 个字符，内容太少".formatted(charCount));
        } else if (charCount < WARN_MIN_CHARS) {
            warnIssues.add("只读到 %d 个字符，可能提取不完整".formatted(charCount));
        }

        // ─────────── ② 乱码率 ───────────
        double badRatio = badCharRatio(rawText);

        if (badRatio > REJECT_BAD_CHAR_RATIO) {
            rejectIssues.add("约 %.0f%% 的字符是乱码（字体编码无法识别）".formatted(badRatio * 100));
        } else if (badRatio > WARN_BAD_CHAR_RATIO) {
            warnIssues.add("有少量乱码（%.0f%%），请核对内容".formatted(badRatio * 100));
        }

        // ─────────── ③ 重复行（页眉页脚） ───────────
        double dupRatio = duplicateLineRatio(rawText);
        if (dupRatio > 0.5) {
            warnIssues.add("重复内容较多（%.0f%%），可能包含重复的页眉页脚".formatted(dupRatio * 100));
        }

        // ─────────── ④ 是否像简历 ───────────
        if (charCount >= WARN_MIN_CHARS && !looksLikeResume(rawText)) {
            warnIssues.add("没有识别到常见的简历板块标题（如「教育背景」「工作经历」）");
        }

        // ─────────── ⑤ 汇总 ───────────
        if (!rejectIssues.isEmpty()) {
            return new ExtractResultVO.Quality(
                    "REJECT",
                    "这个文件的内容无法用于分析",
                    rejectIssues
            );
        }

        if (!warnIssues.isEmpty()) {
            return new ExtractResultVO.Quality(
                    "WARN",
                    "已提取出内容，但请核对下方文本是否完整",
                    warnIssues
            );
        }

        return new ExtractResultVO.Quality("PASS", "读取成功", List.of());
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【乱码率】
     * ═══════════════════════════════════════════════════════════════
     *
     * 【★ 什么算"乱码字符"？】
     *
     *  1. U+FFFD 替换字符 —— "�"
     *     这是"解码失败"的标准标记。PDF 里字体映射缺失时大量出现。
     *
     *  2. Unicode 私有区（U+E000 - U+F8FF）
     *     某些 PDF 把字形编码直接映射到私有区，
     *     视觉上可能是方框或奇怪的符号。
     *
     *  3. 控制字符（除了 \n \r \t）
     *     正常文本不该有。
     *
     *  ★ 为什么不检测"是否全是问号"？
     *    因为问号也可能是正常内容（简历里写"？"）。
     *    U+FFFD 才是可靠的信号。
     *
     * @return 坏字符占比 0.0 - 1.0
     */
    private double badCharRatio(String text) {
        int total = 0;
        int bad = 0;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            i += len;

            // 跳过空白（换行、空格不算内容）
            if (Character.isWhitespace(cp)) continue;

            total++;
            if (isBadChar(cp)) bad++;
        }

        if (total == 0) return 0;
        return (double) bad / total;
    }

    private boolean isBadChar(int cp) {
        // U+FFFD 替换字符
        if (cp == 0xFFFD) return true;
        // Unicode 私有区
        if (cp >= 0xE000 && cp <= 0xF8FF) return true;
        // 控制字符（排除常见空白）
        if (cp < 0x20 && cp != '\n' && cp != '\r' && cp != '\t') return true;
        // 0x7F DEL
        if (cp == 0x7F) return true;

        return false;
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【重复行占比】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么检查这个？
     *   PDF 每页都有页眉（姓名）和页脚（页码）。
     *   5 页简历 = 姓名重复 5 次，页码重复 5 次。
     *   这些内容会被当成"简历正文"参与解析，
     *   虽然影响不大，但会干扰"字数"和"结构完整度"的判断。
     *
     * ★ 阈值 50%：正常简历的重复行很少（<10%）。
     *   超过一半说明页眉页脚占了大量篇幅，值得提醒。
     */
    private double duplicateLineRatio(String text) {
        String[] lines = text.split("\n");
        List<String> nonBlank = new ArrayList<>();

        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) nonBlank.add(t);
        }

        if (nonBlank.size() < 10) return 0;

        long unique = nonBlank.stream().distinct().count();
        return 1.0 - (double) unique / nonBlank.size();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【看起来像简历吗】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 只做很宽松的判断 —— 命中【任意一个】简历特征词就算"像"。
     *   目的是抓住"用户传错文件"（比如传了个说明书/小说）。
     *
     * ★ 为什么宽松？
     *   误判成本不对称：
     *     把简历误判成"不像" → 用户被多提醒一次（小烦）
     *     把非简历误判成"像" → 分析出低分（用户以为简历差）
     *   但提醒太多也很烦，所以只提醒不拒绝。
     */
    private boolean looksLikeResume(String text) {
        String[] markers = {
                "教育", "学历", "工作经历", "工作经验", "实习", "项目",
                "技能", "个人信息", "求职意向", "自我评价",
                "education", "experience", "skills", "project", "summary"
        };

        String lower = text.toLowerCase();
        for (String m : markers) {
            if (lower.contains(m)) return true;
        }
        return false;
    }
}
