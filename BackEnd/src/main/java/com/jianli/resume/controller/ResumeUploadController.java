package com.jianli.resume.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.resume.dto.ExtractResultVO;
import com.jianli.resume.service.ExtractQualityChecker;
import com.jianli.resume.service.ResumeFileExtractor;
import com.jianli.resume.service.ResumeTextNormalizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  简历文件上传解析接口。
 *
 *      POST /api/resumes/extract   （multipart/form-data）
 *      → { text, charCount, quality }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么是"提取"而不是"创建"？】
 *
 *  这是和 ResumeIQ 最重要的设计差异：
 *
 *      ResumeIQ：上传 = 创建 + 分析，一步到位
 *      我们：   上传 = 提取文本 → 用户预览确认 → 才创建简历
 *
 *  ★ 为什么中间要停一下？
 *
 *    1. 用户能看到"系统读成了什么" ——
 *       PDF 解析出错是常态（扫描件/分栏/字体），
 *       让用户确认比事后得到假分数好一万倍
 *
 *    2. 用户可以顺手改 ——
 *       提取出的文本可能少了个板块标题、少了一句话，
 *       在预览框里补上就行，不用回 Word 重来
 *
 *    3. 前端可以加目标岗位 ——
 *       提取接口只负责文本，创建简历时再选岗位
 *
 *  ★ 代价是多一次请求。但这个"多一步"恰恰是产品可靠性的来源。
 */
@Slf4j
@RestController
@RequestMapping("/api/resumes")
@Tag(name = "简历")
@RequiredArgsConstructor
public class ResumeUploadController {

    /**
     * ★ 文件大小上限 5MB。
     *   一份简历 PDF 正常 100KB - 2MB，5MB 足够。
     *   限制大小是为了防止：
     *     1. 用户误传大文件（比如几百页的 PDF）
     *     2. 恶意上传耗尽内存（解析时整个文件会进内存）
     *
     * ★ 这个限制在 Spring 的 multipart 配置里也要设（见 application.yml），
     *   两边都设才安全 —— 这里再检查一次是为了给出更友好的错误信息。
     */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private final ResumeFileExtractor extractor;
    private final ExtractQualityChecker qualityChecker;
    private final ResumeTextNormalizer normalizer;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【上传并提取文本】POST /api/resumes/extract
     * ═══════════════════════════════════════════════════════════════
     *
     * 请求：multipart/form-data，字段名 file
     *
     * 响应：
     *      {
     *        "code": 0,
     *        "data": {
     *          "filename": "resume.pdf",
     *          "text": "张三 | Java 后端...",
     *          "charCount": 860,
     *          "quality": { "level": "PASS", "message": "读取成功", "issues": [] }
     *        }
     *      }
     *
     * ★ 注意：即使 quality 是 REJECT，HTTP 也是 200。
     *   因为"提取这个动作成功完成了"，只是内容质量不行 ——
     *   这是"查询结果"而不是"操作失败"。
     *   前端看 quality.level 决定怎么展示。
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传简历文件并提取文本",
            description = "支持 .pdf 和 .docx。返回文本供用户预览确认，不会直接创建简历")
    public ApiResponse<ExtractResultVO> extract(
            @CurrentUserId Long userId,
            @RequestParam("file") MultipartFile file) throws IOException {

        // ───── ① 基本校验 ─────
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail(
                    com.jianli.common.ErrorCode.PARAM_INVALID, null, "请选择要上传的文件");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ApiResponse.fail(
                    com.jianli.common.ErrorCode.PAYLOAD_TOO_LARGE, null,
                    "文件不能超过 5MB");
        }

        String filename = file.getOriginalFilename();
        log.info("上传简历文件, userId={}, filename={}, size={}KB",
                userId, filename, file.getSize() / 1024);

        /*
         * ───── ② 写到临时文件 ─────
         *
         * ★ 为什么要落临时文件，不直接读 InputStream？
         *   1. PDFBox 的 Loader.loadPDF(File) 需要文件或 byte[]
         *   2. POI 打开大文件时可能多次读取，InputStream 不能重复读
         *
         * ★ 用 Files.createTempFile + try-finally 保证清理。
         *   deleteIfExists 不抛异常，即使删除失败也不影响响应。
         */
        Path temp = Files.createTempFile("resume-upload-", "-" + safeExt(filename));
        try {
            file.transferTo(temp.toFile());

            // ───── ③ 提取文本 ─────
            String rawText = extractor.extract(temp, filename);

            /*
             * ───── ④ 归一化 ─────
             *
             * ★ 为什么在提取后就归一化？
             *   1. 前端预览的文本就是最终会存进简历的文本 ——
             *      用户改之前的"初始状态"应该已经是干净的
             *   2. 字数统计口径和创建简历时一致
             *
             * ★ 归一化会：统一换行、去 BOM、压缩连续空行、去行尾空白。
             *   PDF 提取出的文本经常有大量奇怪的空格和空行。
             */
            String text = normalizer.normalize(rawText);
            int charCount = normalizer.charCount(text);

            // ───── ⑤ 质量检查 ─────
            ExtractResultVO.Quality quality = qualityChecker.check(text);

            log.info("文件提取完成, filename={}, chars={}, quality={}",
                    filename, charCount, quality.level());

            return ApiResponse.ok(new ExtractResultVO(filename, text, charCount, quality));

        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 取安全的扩展名（只用于临时文件名）。
     *
     * ★ 为什么不能直接用原始文件名？
     *   文件名可能包含路径分隔符、特殊字符 ——
     *   直接拼进临时文件路径有路径穿越风险。
     *   ★ 这里只取扩展名，而且过滤掉非字母数字。
     *
     * ★ 这是"不能信任用户输入"的具体体现 —— 文件名也是用户输入。
     */
    private String safeExt(String filename) {
        if (filename == null) return "tmp";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "tmp";
        String ext = filename.substring(dot + 1).toLowerCase();
        return ext.matches("[a-z0-9]{1,5}") ? ext : "tmp";
    }
}
