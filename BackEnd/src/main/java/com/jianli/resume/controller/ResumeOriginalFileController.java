package com.jianli.resume.controller;

import com.jianli.common.ApiResponse;
import com.jianli.common.CurrentUserId;
import com.jianli.resume.service.ResumeOriginalFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 简历原件上传与预览接口。
 */
@RestController
@RequestMapping("/api/resumes/versions")
@Tag(name = "简历原件")
@RequiredArgsConstructor
public class ResumeOriginalFileController {

    private final ResumeOriginalFileService originalFileService;

    @PostMapping("/{versionId}/original")
    @Operation(summary = "上传简历 PDF 原件", description = "原件按版本保存，用于电脑端三栏预览")
    public ApiResponse<Void> upload(
            @CurrentUserId Long userId,
            @PathVariable Long versionId,
            @RequestParam("file") MultipartFile file) throws IOException {

        originalFileService.store(userId, versionId, file);
        return ApiResponse.ok();
    }

    @GetMapping("/{versionId}/original")
    @Operation(summary = "在线预览 PDF 原件")
    public ResponseEntity<Resource> preview(
            @CurrentUserId Long userId,
            @PathVariable Long versionId) {

        return response(userId, versionId, false);
    }

    @GetMapping("/{versionId}/original/download")
    @Operation(summary = "下载 PDF 原件")
    public ResponseEntity<Resource> download(
            @CurrentUserId Long userId,
            @PathVariable Long versionId) {

        return response(userId, versionId, true);
    }

    private ResponseEntity<Resource> response(Long userId, Long versionId, boolean attachment) {
        ResumeOriginalFileService.StoredOriginal stored = originalFileService.load(userId, versionId);
        String dispositionType = attachment ? "attachment" : "inline";
        String encodedFilename = java.net.URLEncoder.encode(stored.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(stored.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        dispositionType + "; filename*=UTF-8''" + encodedFilename)
                .body(new FileSystemResource(stored.path()));
    }
}