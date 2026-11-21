package com.jianli.jd.dto;

import com.jianli.ai.model.JdParsed;
import com.jianli.jd.entity.JobDescription;

import java.time.LocalDateTime;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  JD 详情。含原文 + 解析结果。
 *
 *  ★ 用于"JD 列表"和"JD 详情页"共用 ——
 *    列表页不需要解析结果，但传了也无害（JD 解析结果只有几 KB）。
 *    真要优化可以拆两个 VO，本项目不必。
 * ═══════════════════════════════════════════════════════════════════════
 */
public record JdVO(
        Long id,
        String title,
        String company,
        String content,
        String parseStatus,
        String parsedSource,
        JdParsed parsed,
        LocalDateTime parsedAt,
        LocalDateTime updatedAt
) {

    /**
     * 从实体转换。
     *
     * @param parsed 解析结果（由 Service 反序列化后传入，可能为 null）
     */
    public static JdVO of(JobDescription jd, JdParsed parsed) {
        return new JdVO(
                jd.getId(),
                jd.getTitle(),
                jd.getCompany(),
                jd.getContent(),
                jd.getParseStatus(),
                jd.getParsedSource(),
                parsed,
                jd.getParsedAt(),
                jd.getUpdatedAt()
        );
    }
}
