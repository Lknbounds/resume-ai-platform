package com.jianli.analysis.controller;

import com.jianli.analysis.dto.AnalysisReportVO;
import com.jianli.analysis.dto.CreateAnalysisRequest;
import com.jianli.analysis.service.AnalysisService;
import com.jianli.analysis.service.ReportPdfService;
import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.common.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  分析相关接口。4 个。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么 create 返回 201 而不是 202（Accepted）？】
 *
 *  按最初设计，分析是异步的，应该返回 202 + reportId 让前端轮询。
 *  但现在是【同步】执行的（规则解析只要几毫秒），
 *  请求结束时报告已经算完了 —— 所以直接返回 201 + 完整报告更合适。
 *
 *  ★ 接 AI 之后会改成异步：
 *      POST → 202 {reportId, status:"PENDING"}
 *      GET /{id} 轮询 或 SSE 推进度
 *   接口签名不用变，只是返回的状态和内容不同。
 */
@RestController
@RequestMapping("/api/analyses")
@Tag(name = "分析")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final ReportPdfService reportPdfService;

    /**
     * 发起分析。
     *
     * 请求：{"resumeVersionId":2,"jdId":1}
     * 响应 201：完整的报告（分数、维度、关键词、建议）
     */
    @PostMapping
    @Operation(summary = "发起分析", description = "同步计算，返回完整报告")
    public ResponseEntity<ApiResponse<AnalysisReportVO>> create(
            @CurrentUserId Long userId,
            @RequestBody @Valid CreateAnalysisRequest req) {

        AnalysisReportVO vo = analysisService.create(
                userId,
                req.resumeVersionId(),
                req.jdId(),
                Boolean.TRUE.equals(req.forceRefresh()));

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(vo));
    }

    /**
     * 报告详情。
     *
     * ★ 前端报告页初始化时调这个。
     */
    @GetMapping("/{id}")
    @Operation(summary = "报告详情")
    public ApiResponse<AnalysisReportVO> detail(@CurrentUserId Long userId,
                                                 @PathVariable Long id) {
        return ApiResponse.ok(analysisService.detail(userId, id));
    }

    /**
     * 我的分析历史。
     *
     * @param resumeVersionId 可选。传了就只看这个版本的分析记录
     */
    @GetMapping
    @Operation(summary = "分析历史")
    public ApiResponse<PageResult<AnalysisReportVO>> list(
            @CurrentUserId Long userId,
            @RequestParam(required = false) Long resumeVersionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        return ApiResponse.ok(analysisService.list(userId, resumeVersionId, page, size));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除报告")
    public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
        analysisService.delete(userId, id);
        return ApiResponse.ok();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【导出 PDF】GET /api/analyses/{id}/export/pdf
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应：Content-Type: application/pdf，直接是文件流（不是 JSON）
     *
     * 【★ 为什么这里返回 ResponseEntity<byte[]> 而不是 ApiResponse？】
     *
     *  ApiResponse 会被 Jackson 序列化成 JSON。
     *  但导出 PDF 要传的是【二进制文件】，不是 JSON。
     *  必须直接用 ResponseEntity 控制响应体类型。
     *
     * 【★ Content-Disposition 是干什么的？】
     *
     *  attachment; filename="..." → 浏览器弹出"下载"而不是"在标签页里打开"
     *  inline                     → 浏览器直接预览
     *
     *  我们选 attachment（下载），因为用户通常要转发给别人。
     *
     *  ★ 文件名为什么用 filename*=UTF-8''... ？
     *    HTTP 头只能放 ASCII。中文文件名直接写会乱码或报错。
     *    用 RFC 5987 的编码格式：filename*=UTF-8''<percent-encoded>
     *    现代浏览器都支持。
     */
    @GetMapping("/{id}/export/pdf")
    @Operation(summary = "导出 PDF 报告")
    public ResponseEntity<byte[]> exportPdf(@CurrentUserId Long userId,
                                             @PathVariable Long id) {

        AnalysisReportVO report = analysisService.detail(userId, id);
        byte[] pdf = reportPdfService.render(report);

        // 文件名：简历标题 + 岗位 + 日期
        String baseName = (report.resumeTitle() == null ? "简历分析" : report.resumeTitle())
                + "-" + (report.jdTitle() == null ? "岗位" : report.jdTitle())
                + "-" + java.time.LocalDate.now();
        // 去掉文件名里的非法字符
        String safe = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String encoded = java.net.URLEncoder.encode(safe + ".pdf",
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .body(pdf);
    }
}
