package com.jianli.community.adopt.dto;

import jakarta.validation.constraints.Size;

/**
 * 采纳社区建议。
 *
 * updatedResumeContent 为空 = 仅标记采纳；
 * 不为空 = 基于帖子来源简历新增一个 ADOPT_COMMENT 版本。
 */
public record AdoptRequest(
        @Size(max = 200000, message = "简历正文过长")
        String updatedResumeContent,

        @Size(max = 255, message = "变更说明最长 255 个字符")
        String changeNote
) {}
