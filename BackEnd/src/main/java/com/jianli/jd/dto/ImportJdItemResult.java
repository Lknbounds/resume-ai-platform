package com.jianli.jd.dto;

/**
 * 单条 JD 导入结果。
 *
 * status:
 *   IMPORTED  → 已入库
 *   DUPLICATE → 已存在，跳过
 *   FAILED    → 该条格式或内容不合法
 */
public record ImportJdItemResult(
        int index,
        String title,
        String company,
        String status,
        String message,
        Long jdId
) {}