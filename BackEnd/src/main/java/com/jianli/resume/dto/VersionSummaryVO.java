package com.jianli.resume.dto;

import com.jianli.resume.entity.ResumeVersion;
import com.jianli.resume.enums.VersionSource;

import java.time.LocalDateTime;

/**
 * 版本摘要，不含简历正文。
 */
public record VersionSummaryVO(
        Long id,
        Integer versionNo,
        VersionSource source,
        String changeNote,
        Integer charCount,
        boolean hasOriginal,
        String originalFileName,
        Long originalFileSize,
        LocalDateTime originalUploadedAt,
        LocalDateTime createdAt
) {
    public static VersionSummaryVO of(ResumeVersion version) {
        return new VersionSummaryVO(
                version.getId(),
                version.getVersionNo(),
                version.getSource(),
                version.getChangeNote(),
                version.getCharCount(),
                version.getOriginalFilePath() != null,
                version.getOriginalFileName(),
                version.getOriginalFileSize(),
                version.getOriginalUploadedAt(),
                version.getCreatedAt()
        );
    }
}