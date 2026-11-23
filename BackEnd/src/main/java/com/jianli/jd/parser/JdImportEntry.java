package com.jianli.jd.parser;

/**
 * 文件中解析出的一个岗位条目。
 * index 是文件内的顺序号，用于把导入结果准确回传给用户。
 */
public record JdImportEntry(
        int index,
        String title,
        String company,
        String content
) {}