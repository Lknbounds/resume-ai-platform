package com.jianli.resume.dto;

import com.jianli.resume.entity.ResumeVersion;
import com.jianli.resume.enums.VersionSource;

import java.time.LocalDateTime;

/**
 * 单个简历版本详情，含正文和原件元信息。
 */
public record VersionDetailVO(
        Long id,
        Long resumeId,
        Integer versionNo,
        String content,
        Integer charCount,
        String contentHash,
        VersionSource source,
        Long parentVersionId,
        String changeNote,
        boolean hasOriginal,
        String originalFileName,
        Long originalFileSize,
        LocalDateTime originalUploadedAt,
        LocalDateTime createdAt
) {
    public static VersionDetailVO of(ResumeVersion version) {
        return new VersionDetailVO(
                version.getId(),
                version.getResumeId(),
                version.getVersionNo(),
                version.getContent(),
                version.getCharCount(),
                version.getContentHash(),
                version.getSource(),
                version.getParentVersionId(),
                version.getChangeNote(),
                version.getOriginalFilePath() != null,
                version.getOriginalFileName(),
                version.getOriginalFileSize(),
                version.getOriginalUploadedAt(),
                version.getCreatedAt()
        );
    }
}