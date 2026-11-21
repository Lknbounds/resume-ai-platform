package com.jianli.analysis.service;

import com.jianli.analysis.dto.AnalysisReportVO;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  把分析报告导出成 PDF。HTML + CSS → PDF（用 OpenHTMLToPDF）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      用户点"下载报告"
 *         ↓
 *      GET /api/analyses/{id}/export/pdf
 *         ↓
 *      AnalysisService.detail()   → 拿到 AnalysisReportVO
 *         ↓
 *      【本类】render(report)     → HTML 字符串
 *         ↓
 *      OpenHTMLToPDF 渲染        → byte[]
 *         ↓
 *      HTTP 响应：Content-Type: application/pdf
 *         ↓
 *      浏览器直接下载
 *
 *  【★★ 为什么用 HTML+CSS 而不是直接画 PDF？】
 *
 *  用 PDFBox 直接画：
 *      - 每个文字要手动算 x/y 坐标
 *      - 换行、分页要自己实现
 *      - 画进度条要算矩形宽高
 *      → 一份报告几百行坐标代码，改布局全部重来
 *
 *  用 HTML+CSS：
 *      - 写 div 和 CSS，浏览器怎么排 PDF 就怎么排
 *      - 分页用 CSS 的 page-break-inside: avoid
 *      - 改布局只改样式，不动 Java
 *
 *  ★ 这是"用对的抽象层级"——排版的事交给排版引擎。
 *
 *  【★★ 中文字体必须内嵌】
 *
 *  PDF 不像网页，没有"系统默认字体"。
 *  不指定字体时默认用 Helvetica，它【没有中文字形】，
 *  中文会渲染成空白或方框 —— 这是最常见的"导出 PDF 后发现中文全没了"。
 *
 *  所以：
 *    1. 字体文件放在 resources/fonts/simhei.ttf（9.4MB，跟着 jar 走）
 *    2. 渲染时用 builder.useFont() 注册
 *    3. CSS 里指定 font-family: 'SimHei'
 *
 *  ★ 为什么不用 win 系统的 C:\Windows\Fonts？
 *    那样部署到 Linux 服务器就没这个文件了，导出会失败。
 *    打进 jar 里，任何环境都能用。
 */
@Slf4j
@Service
public class ReportPdfService {

    /** 字体在 classpath 里的路径 */
    private static final String FONT_PATH = "fonts/simhei.ttf";

    /** CSS 里用的字体名，必须和 useFont 注册的名字一致 */
    private static final String FONT_FAMILY = "SimHei";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【主入口：生成 PDF 字节数组】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param report 报告数据
     * @return PDF 文件的字节内容
     * @throws RuntimeException 渲染失败
     */
    public byte[] render(AnalysisReportVO report) {
        long start = System.currentTimeMillis();

        String html = buildHtml(report);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PdfRendererBuilder builder = new PdfRendererBuilder();

            /*
             * ═══════════════════════════════════════════════════════
             *  【★★ 注册中文字体 —— 这里有个踩过的坑】
             * ═══════════════════════════════════════════════════════
             *
             * ❌ 错误写法（第一版）：
             *      try (InputStream font = resource.getInputStream()) {
             *          builder.useFont(() -> font, "SimHei");
             *      }                          // ← 流在这里就关闭了
             *      builder.run();             // ← 此时读流 → 失败 → 中文全是 #
             *
             *   useFont 接收的是 FSSupplier<InputStream>，**延迟到 run() 才调用**。
             *   try-with-resources 先关闭了流，run() 再读就读不到了。
             *   OpenHTMLToPDF 不报错，只是找不到字形，静默渲染成 #。
             *
             * ✅ 正确写法：
             *      先把字体读进 byte[]，supplier 每次返回【新的】流。
             *
             * ★ 这个 bug 很难发现：PDF 生成"成功"了，只是中文全变井号。
             *   必须打开 PDF 看才知道 —— 所以验证时一定要检查内容，不能只看 HTTP 200。
             */
            final byte[] fontBytes = loadFontBytes();
            builder.useFont(() -> new java.io.ByteArrayInputStream(fontBytes), FONT_FAMILY);

            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();

            byte[] bytes = out.toByteArray();
            log.info("PDF 导出完成, reportId={}, 大小={}KB, 耗时={}ms",
                    report.id(), bytes.length / 1024, System.currentTimeMillis() - start);
            return bytes;

        } catch (Exception e) {
            log.error("PDF 导出失败, reportId={}", report.id(), e);
            throw new IllegalStateException("PDF 生成失败", e);
        }
    }

    /**
     * 把字体文件读进内存。
     *
     * ★ 为什么读进 byte[] 而不是保持 InputStream？
     *   见 render 方法里的说明 —— 流会被提前关闭。
     *
     * ★ 为什么不每次重新打开流？
     *   字体 9.4MB，每次读盘 + 解析都慢。
     *   读进数组后，supplier 每次返回 ByteArrayInputStream（轻量，只是包一层）。
     *
     * ★ 要不要缓存这个 byte[] 到字段？
     *   可以，但要注意：Spring 单例 Bean 里存 9.4MB 数组，常驻内存。
     *   ★ 本项目报告导出是低频操作，每次读一次盘（几十毫秒）完全可以接受。
     *     如果变成高频，再加 @PostConstruct 预加载。
     */
    private byte[] loadFontBytes() throws java.io.IOException {
        try (InputStream in = new ClassPathResource(FONT_PATH).getInputStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【组装 HTML】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 用 StringBuilder 拼而不是模板引擎（Thymeleaf/Freemarker）：
     *   只有这一个模板，引一个模板引擎不划算。
     *   如果以后模板多了，再引 Thymeleaf。
     */
    private String buildHtml(AnalysisReportVO r) {
        StringBuilder h = new StringBuilder(8192);

        h.append("""
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8"/>
                <style>
                @page { size: A4; margin: 18mm 16mm; }
                * { box-sizing: border-box; }
                body { font-family: 'SimHei', sans-serif; font-size: 10.5pt;
                       color: #121721; line-height: 1.6; margin: 0; }
                .header { text-align: center; padding-bottom: 16px;
                          border-bottom: 2px solid #29a399; margin-bottom: 20px; }
                .brand { font-size: 11pt; color: #29a399; letter-spacing: 2px; margin-bottom: 10px; }
                .score { font-size: 42pt; font-weight: bold; line-height: 1; }
                .grade { font-size: 13pt; margin-top: 6px; }
                .context { font-size: 9pt; color: #6b7280; margin-top: 10px; }
                .vs { margin: 0 10px; color: #c7c9d1; font-size: 8.5pt; }
                .summary { background: #f0f9f8; border-left: 3px solid #29a399;
                           padding: 10px 14px; margin-bottom: 18px; font-size: 10pt; }
                h2 { font-size: 11pt; margin: 18px 0 10px 0; padding-bottom: 5px;
                     border-bottom: 1px solid #e5e7eb; }
                table { width: 100%; border-collapse: collapse; margin-bottom: 12px; }
                th, td { text-align: left; padding: 6px 8px; font-size: 9.5pt;
                         border-bottom: 1px solid #f0f0f3; }
                th { color: #6b7280; font-weight: normal; font-size: 9pt; }
                .num { text-align: right; font-weight: bold; }
                .bar-bg { background: #eef0f3; height: 6px; border-radius: 3px; width: 100%; }
                .bar { height: 6px; border-radius: 3px; }
                .dim-detail { color: #9aa1ae; font-size: 8.5pt; }
                .strength { padding: 3px 0 3px 14px; position: relative; font-size: 10pt; }
                .strength:before { content: '·'; position: absolute; left: 4px;
                                   color: #16a34a; font-weight: bold; }
                .tag { display: inline-block; padding: 2px 7px; margin: 0 5px 5px 0;
                       border-radius: 3px; font-size: 9pt; }
                .tag-ok { background: #e8f7f0; color: #15803d; }
                .tag-miss { background: #fdeaea; color: #b91c1c; }
                .tag-warn { background: #fdf3e6; color: #b45309; }
                .tag-muted { background: #f0f1f3; color: #6b7280; }
                .sug { border-left: 2px solid #d1d5db; padding: 8px 12px; margin-bottom: 10px;
                       page-break-inside: avoid; font-size: 9.5pt; }
                .sug-high { border-left-color: #dc2626; }
                .sug-med { border-left-color: #d97706; }
                .sug-issue { font-weight: bold; margin-bottom: 4px; }
                .sug-ev { color: #6b7280; background: #fafbfc; padding: 5px 8px;
                          margin: 5px 0; border-radius: 3px; font-size: 9pt; }
                .sug-act { color: #4b5563; }
                .footer { margin-top: 24px; padding-top: 10px; border-top: 1px solid #e5e7eb;
                          text-align: center; font-size: 8.5pt; color: #9aa1ae; }
                .page-break { page-break-before: always; }
                </style>
                </head>
                <body>
                """);

        // ═══════════ 页头：分数 ═══════════
        String color = gradeColor(r.grade());
        h.append("<div class='header'>")
                .append("<div class='brand'>简 评 · 简历分析报告</div>")
                .append("<div class='score' style='color: ").append(color).append("'>")
                .append(r.overallScore() == null ? "-" : String.format("%.0f", r.overallScore()))
                .append("</div>")
                .append("<div class='grade' style='color: ").append(color).append("'>")
                .append(gradeLabel(r.grade()))
                .append("</div>")
                .append("<div class='context'>")
                .append(esc(nvl(r.resumeTitle()))).append(" · v").append(nvl(String.valueOf(r.resumeVersionNo())))
                .append("<span class='vs'>vs</span>")
                .append(esc(nvl(r.jdTitle())))
                .append("</div>")
                .append("</div>");

        // ═══════════ 摘要 ═══════════
        if (r.summary() != null) {
            h.append("<div class='summary'>").append(esc(r.summary())).append("</div>");
        }

        // ═══════════ 优势 ═══════════
        if (r.strengths() != null && !r.strengths().isEmpty()) {
            h.append("<h2>优势</h2>");
            for (String s : r.strengths()) {
                h.append("<div class='strength'>").append(esc(s)).append("</div>");
            }
        }

        // ═══════════ 技能匹配 ═══════════
        h.append("<h2>技能匹配</h2>");

        h.append("<table><tr><th style='width:70px'>已匹配</th><td>");
        appendTags(h, r.hits(), "tag-ok");
        h.append("</td></tr>");

        if (r.partials() != null && !r.partials().isEmpty()) {
            h.append("<tr><th>部分命中</th><td>");
            appendTags(h, r.partials(), "tag-warn");
            h.append("</td></tr>");
        }

        h.append("<tr><th>缺失</th><td>");
        appendTags(h, r.missing(), "tag-miss");
        h.append("</td></tr>");

        if (r.softSkills() != null && !r.softSkills().isEmpty()) {
            h.append("<tr><th>软技能</th><td>");
            for (String s : r.softSkills()) {
                h.append("<span class='tag tag-muted'>").append(esc(s)).append("</span>");
            }
            h.append("<span style='font-size:8.5pt;color:#9aa1ae'>（不计入评分）</span>");
            h.append("</td></tr>");
        }
        h.append("</table>");

        // ═══════════ 评分构成 ═══════════
        h.append("<h2>评分构成</h2>");
        h.append("<table>");
        if (r.dimensions() != null) {
            for (var d : r.dimensions()) {
                double score = d.score() == null ? 0 : d.score();
                boolean applicable = Boolean.TRUE.equals(d.applicable());

                h.append("<tr>")
                        .append("<td style='width:110px'>").append(esc(d.name())).append("</td>")
                        .append("<td>");
                if (applicable) {
                    h.append("<div class='bar-bg'><div class='bar' style='width:")
                            .append((int) score).append("%;background:")
                            .append(barColor(score)).append("'></div></div>")
                            .append("<div class='dim-detail'>").append(esc(nvl(d.detail()))).append("</div>");
                } else {
                    h.append("<span class='dim-detail'>不适用（").append(esc(nvl(d.detail()))).append("）</span>");
                }
                h.append("</td>")
                        .append("<td class='num' style='width:60px;color:").append(barColor(score)).append("'>")
                        .append(applicable ? String.format("%.1f", score) : "-")
                        .append("</td>")
                        .append("<td style='width:50px;font-size:9pt;color:#9aa1ae'>")
                        .append(applicable ? String.format("%.0f%%", d.weight() * 100) : "")
                        .append("</td>")
                        .append("</tr>");
            }
        }
        h.append("</table>");

        // ═══════════ 改进建议 ═══════════
        if (r.suggestions() != null && !r.suggestions().isEmpty()) {
            h.append("<div class='page-break'></div>");
            h.append("<h2>改进建议</h2>");
            for (var s : r.suggestions()) {
                String cls = "HIGH".equals(s.severity()) ? "sug-high"
                        : "MEDIUM".equals(s.severity()) ? "sug-med" : "";
                h.append("<div class='sug ").append(cls).append("'>")
                        .append("<div class='sug-issue'>").append(esc(s.issue())).append("</div>");
                if (s.evidence() != null && !s.evidence().isBlank()) {
                    h.append("<div class='sug-ev'>原文：").append(esc(s.evidence())).append("</div>");
                }
                h.append("<div class='sug-act'>").append(esc(nvl(s.suggestion()))).append("</div>")
                        .append("</div>");
            }
        }

        // ═══════════ 页脚 ═══════════
        h.append("<div class='footer'>")
                .append("由「简评」生成 · ").append(LocalDateTime.now().format(TIME_FMT))
                .append(" · 评分算法 ").append(esc(nvl(r.scoringVersion())))
                .append("</div>");

        h.append("</body></html>");
        return h.toString();
    }

    /** 拼接标签 */
    private void appendTags(StringBuilder h, List<AnalysisReportVO.KeywordVO> list, String cls) {
        if (list == null || list.isEmpty()) {
            h.append("<span style='color:#9aa1ae;font-size:9pt'>无</span>");
            return;
        }
        for (var k : list) {
            h.append("<span class='tag ").append(cls).append("'>")
                    .append(esc(k.name())).append("</span>");
        }
    }

    private String gradeColor(String grade) {
        return switch (grade == null ? "" : grade) {
            case "EXCELLENT" -> "#16a34a";
            case "GOOD" -> "#2563eb";
            case "FAIR" -> "#d97706";
            case "WEAK" -> "#dc2626";
            default -> "#9aa1ae";
        };
    }

    private String gradeLabel(String grade) {
        return switch (grade == null ? "" : grade) {
            case "EXCELLENT" -> "优秀 · 与目标岗位高度匹配";
            case "GOOD" -> "良好 · 整体匹配良好";
            case "FAIR" -> "一般 · 有一定基础，仍有提升空间";
            case "WEAK" -> "偏低 · 与目标岗位差距较大";
            default -> "";
        };
    }

    private String barColor(double score) {
        if (score >= 85) return "#16a34a";
        if (score >= 70) return "#2563eb";
        if (score >= 55) return "#d97706";
        return "#dc2626";
    }

    /**
     * HTML 转义。
     *
     * ★ 必须做！报告内容里可能有简历原文（用户输入），
     *   里面有 < > & 会破坏 HTML 结构，甚至能注入脚本。
     *
     *   ★ 即使 PDF 不执行 JS，"结构被破坏"也会导致排版错乱。
     *     所以所有用户可控的内容都要转义。
     */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
