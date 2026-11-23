package com.jianli.community.adopt.dto;

/**
 * 采纳结果。
 *
 * newVersionId 为空表示本次只标记了采纳，没有生成新版本。
 */
public record AdoptResultVO(
        Long commentId,
        Boolean adopted,
        Long newVersionId,
        Integer versionNo,
        Long resumeId,
        Long analysisReportId
) {}
