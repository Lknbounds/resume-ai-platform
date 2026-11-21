package com.jianli.resume.service;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  从上传的 PDF / DOCX 文件里抽出纯文本。
 *
 *  ★ 它的产出就是一个 String —— 之后走的是和"粘贴文本"完全一样的流程。
 *    解析结果【不入库】，回给前端预览，用户确认后才创建简历。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      用户上传 resume.pdf
 *         ↓
 *      POST /api/resumes/extract（multipart/form-data）
 *         ↓
 *      【本类】extract(path, filename)
 *         ├─ 按扩展名分流：.pdf → PDFBox / .docx → POI
 *         └─ 返回纯文本
 *         ↓
 *      ResumeController 检查质量（字数、乱码率）
 *         ↓
 *      返回 { text, quality: {...} } 给前端预览
 *         ↓
 *      用户确认 → 调已有的 POST /api/resumes 创建简历
 *
 *  ★ 关键：解析和创建是【两步】。
 *    解析出来的文本用户能看见、能改 —— 这是避免"垃圾文本直接进分析"的关键。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么不用 spring-ai-pdf-document-reader？】
 *
 *  那个 PagePdfDocumentReader 是为 RAG 设计的：
 *      PDF → List<Document>（带分页元数据、可能被切块）
 *  我们只要"一整段纯文本"，用底层 PDFBox 的 PDFTextStripper 更直接，
 *  少一层抽象，也少一个依赖。
 *
 *  【★ 已知的能力边界（诚实写在这里）】
 *
 *  能干：
 *      数字生成的 PDF（Word/WPS/超级简历导出）  ✅
 *      .docx 的段落和表格                        ✅
 *
 *  干不了：
 *      扫描件（图片型 PDF）→ 提取出空字符串
 *      中文 CID 字体缺 ToUnicode → 提取出乱码
 *      双栏排版 → 左右栏交替，读不成句（但不会乱码）
 *
 *  ★ 这三种情况本类【不会报错】，而是返回质量很差的文本。
 *    所以 Controller 必须做质量检查 —— 见 qualityCheck。
 */
@Slf4j
@Component
public class ResumeFileExtractor {

    /** 允许的扩展名 */
    private static final String EXT_PDF = ".pdf";
    private static final String EXT_DOCX = ".docx";

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【提取主入口】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param path     临时文件路径
     * @param filename 原始文件名（用来判断扩展名和报错信息）
     * @return 提取出的纯文本（可能为空字符串 —— 交给调用方做质量判断）
     * @throws BizException 文件格式不支持 / 文件损坏
     */
    public String extract(Path path, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);

        try {
            if (name.endsWith(EXT_PDF)) {
                return extractPdf(path);
            }
            if (name.endsWith(EXT_DOCX)) {
                return extractDocx(path);
            }

            /*
             * ★ 不支持 .doc（老格式）
             *   需要 poi-scratchpad 的 HWPFDocument，另一套 API，
             *   而且中文编码经常出错。明确拒绝比给个坏结果好。
             */
            if (name.endsWith(".doc")) {
                throw new BizException(ErrorCode.PARAM_INVALID, null,
                        "暂不支持 .doc 格式，请用 Word 另存为 .docx 后再上传");
            }
            if (name.endsWith(".pdf") || name.endsWith(".wps")) {
                throw new BizException(ErrorCode.PARAM_INVALID, null,
                        "暂不支持 WPS 格式，请另存为 .docx 或 .pdf");
            }

            throw new BizException(ErrorCode.PARAM_INVALID, null,
                    "只支持 .pdf 和 .docx 文件");

        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            /*
             * ★ 文件损坏 / 加密 / 不是真正的 PDF
             *   不把底层异常暴露给用户（可能是 "Invalid dictionary" 这种莫名其妙的话）
             */
            log.warn("文件解析失败, filename={}", filename, e);
            throw new BizException(ErrorCode.PARAM_INVALID, null,
                    "文件解析失败，请确认文件未损坏且未加密");
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【PDF 提取】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★★ 用的是 PDFBox 2.x 的 API：PDDocument.load(File)
     *   3.x 是 Loader.loadPDF(File) —— 网上很多教程是 3.x 的，照抄会编译不过。
     *
     *   本项目锁 2.0.37，原因是 OpenHTMLToPDF（导出 PDF 用）
     *   内部依赖 2.0.24，两者必须同版本，否则报 NoSuchFieldError。
     *   详见 pom.xml 里 pdfbox.version 的注释。
     *
     * ★ PDFTextStripper 默认按"阅读顺序"输出（sortByPosition=false）：
     *   它按 PDF 内部的对象顺序吐字符，不一定等于视觉顺序。
     *
     *   setSortByPosition(true) 会按坐标排序 ——
     *   ★ 单栏简历这样更准（不会出现同行的左右两段被拆开），
     *     但双栏简历会更乱（左右栏被强行按 y 坐标混在一起）。
     *
     *   本项目选 false（默认）：单栏简历占绝大多数。
     */
    private String extractPdf(Path path) throws IOException {
        try (PDDocument doc = PDDocument.load(path.toFile())) {

            /*
             * ★ 加密的 PDF 无法提取。
             */
            if (doc.isEncrypted()) {
                throw new BizException(ErrorCode.PARAM_INVALID, null,
                        "PDF 已加密，无法读取。请先解除密码保护");
            }

            LayoutPdfTextStripper stripper = new LayoutPdfTextStripper();
            String text = stripper.extract(doc);

            log.debug("PDF 提取完成: {} 页, {} 字符", doc.getNumberOfPages(), text.length());
            return text;
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【DOCX 提取】★ 必须同时读段落和表格
     * ═══════════════════════════════════════════════════════════════
     *
     * 【★★ 这里有个 ResumeIQ 踩过的坑，我们绕开了】
     *
     * 它的 Python 代码：
     *     '\n'.join(p.text for p in doc.paragraphs)
     *
     * ★ doc.paragraphs【不包含表格里的文字】！
     *   很多简历用表格排版（左边公司、右边时间），
     *   这样提取出来会丢掉大量内容 —— 而且是【静默丢失】，
     *   用户看到的是一份"内容少了一半"的简历，分析出的分数自然偏低。
     *
     * 本类的做法：按文档中【元素的实际顺序】遍历，
     *   段落和表格交错输出，保持简历的原始结构。
     *
     * ★ 怎么保持顺序？
     *   doc.getBodyElements() 返回的是 IBodyElement 列表，
     *   每个元素可能是 XWPFParagraph 或 XWPFTable，顺序就是文档里的顺序。
     */
    private String extractDocx(Path path) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(in)) {

            StringBuilder sb = new StringBuilder();

            for (var element : doc.getBodyElements()) {

                if (element instanceof XWPFParagraph p) {
                    String text = p.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append(text).append('\n');
                    }

                } else if (element instanceof XWPFTable table) {
                    /*
                     * ★ 表格怎么转成文本？
                     *   简历里的表格通常是"公司 | 时间"或"技能 | 熟练度"。
                     *   用空格把同一行的单元格连起来，保留"这一行是一个整体"的语义。
                     *
                     *   ★ 为什么不用 \t？
                     *     制表符在后面被 normalize 时会被保留，
                     *     但技能词典是按"词间距"匹配的，制表符可能干扰。
                     *     用空格更安全。
                     */
                    for (XWPFTableRow row : table.getRows()) {
                        StringBuilder rowText = new StringBuilder();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String cellText = cell.getText();
                            if (cellText != null && !cellText.isBlank()) {
                                if (rowText.length() > 0) rowText.append(' ');
                                rowText.append(cellText.trim());
                            }
                        }
                        if (rowText.length() > 0) {
                            sb.append(rowText).append('\n');
                        }
                    }
                }
            }

            String text = sb.toString();
            log.debug("DOCX 提取完成: {} 字符", text.length());
            return text;
        }
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 更进一步：双栏重排为什么不做？】
     *
     *  技术上可行：用 PDFTextStripper 的子类在 writeString 里
     *  收集每个 TextPosition 的 (x, y)，然后：
     *      1. 按 y 聚类成行
     *      2. 统计 x 坐标分布，出现双峰 → 判定双栏
     *      3. 双栏时先输出左栏（x < 中线）再输出右栏
     *
     *  约 60 行代码。
     *
     *  ★ 为什么现在不做？
     *    1. 中文简历里双栏比例不高（远低于英文简历）
     *    2. 双栏乱序【不会乱码】，只是句子被交错，
     *       技能关键词仍然能被词典匹配到 —— 分数受影响但不会完全失真
     *    3. 优先级不如先把社区/导出做完
     *
     *  ★ 如果以后要做，就在这个类里加一个 PDFTextStripper 的子类实现。
     * ═══════════════════════════════════════════════════════════════
     */
}
