package com.jianli.jd.dto;

import java.util.List;

/**
 * JD 文件批量导入摘要。
 */
public record ImportJdResult(
        String fileName,
        int total,
        int success,
        int duplicate,
        int failed,
        List<ImportJdItemResult> items
) {}