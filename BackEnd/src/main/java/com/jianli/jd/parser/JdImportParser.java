package com.jianli.jd.parser;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JD Markdown 文件解析器。
 *
 * 支持的主要格式：
 *   ## 1. Java 后端开发工程师
 *   - 公司：某科技有限公司
 *   **岗位描述 / 任职资格**
 *   ```text
 *   岗位职责...
 *   ```
 *
 * 也兼容：
 *   - ## 岗位名称（不带序号）
 *   - 没有代码块时，直接使用标题下的普通正文
 *   - 多个条目用 --- 分隔
 */
@Component
public class JdImportParser {

    private static final Pattern HEADING = Pattern.compile("^##\\s*(.*?)\\s*$");
    private static final Pattern NUMBER_PREFIX =
            Pattern.compile("^(?:\\d+)\\s*[.、]\\s*(.+)$");
    private static final Pattern COMPANY_LINE =
            Pattern.compile("^\\s*[-*]\\s*公司\\s*[:：]\\s*(.*?)\\s*$");
    private static final Pattern METADATA_LINE =
            Pattern.compile("^\\s*[-*]\\s*(?:公司|列表岗位名|技能标签)\\s*[:：].*$");
    private static final Pattern DESCRIPTION_LABEL =
            Pattern.compile("^\\s*\\*{0,2}岗位描述\\s*[/／]\\s*任职资格\\*{0,2}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*```.*$");

    public List<JdImportEntry> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.JD_IMPORT_FORMAT_INVALID, null,
                    "文件内容为空，请选择包含岗位 JD 的 .md 或 .txt 文件");
        }

        String text = raw.replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String[] lines = text.split("\n", -1);

        List<Integer> headingIndexes = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (HEADING.matcher(lines[i]).matches()) {
                headingIndexes.add(i);
            }
        }

        if (headingIndexes.isEmpty()) {
            throw new BizException(ErrorCode.JD_IMPORT_FORMAT_INVALID, null,
                    "没有识别到岗位标题。每个岗位必须以“## 岗位名称”开头");
        }

        List<JdImportEntry> entries = new ArrayList<>();
        for (int i = 0; i < headingIndexes.size(); i++) {
            int start = headingIndexes.get(i);
            int end = i + 1 < headingIndexes.size() ? headingIndexes.get(i + 1) : lines.length;
            Matcher heading = HEADING.matcher(lines[start]);
            if (!heading.matches()) {
                continue;
            }

            String title = cleanTitle(heading.group(1));
            List<String> section = new ArrayList<>();
            for (int line = start + 1; line < end; line++) {
                section.add(lines[line]);
            }

            entries.add(new JdImportEntry(
                    entries.size() + 1,
                    title,
                    extractCompany(section),
                    extractContent(section)
            ));
        }

        if (entries.isEmpty()) {
            throw new BizException(ErrorCode.JD_IMPORT_FORMAT_INVALID, null,
                    "没有识别到岗位标题。每个岗位必须以“## 岗位名称”开头");
        }
        return entries;
    }

    private String cleanTitle(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        Matcher numbered = NUMBER_PREFIX.matcher(title);
        if (numbered.matches()) {
            title = numbered.group(1).trim();
        }
        title = title.replaceFirst("^[*_`\\s]+", "")
                .replaceFirst("[*_`\\s]+$", "");
        return title;
    }

    private String extractCompany(List<String> section) {
        for (String line : section) {
            Matcher matcher = COMPANY_LINE.matcher(line);
            if (matcher.matches()) {
                String company = matcher.group(1).trim();
                return company.isEmpty() ? null : company;
            }
        }
        return null;
    }

    private String extractContent(List<String> section) {
        List<String> fenced = extractFencedBlocks(section);
        if (!fenced.isEmpty()) {
            return trimBlankLines(fenced);
        }

        List<String> fallback = new ArrayList<>();
        for (String line : section) {
            String trimmed = line.trim();
            if (FENCE.matcher(line).matches()
                    || METADATA_LINE.matcher(line).matches()
                    || DESCRIPTION_LABEL.matcher(line).matches()
                    || trimmed.equals("---")) {
                continue;
            }
            fallback.add(line);
        }
        return trimBlankLines(fallback);
    }

    private List<String> extractFencedBlocks(List<String> section) {
        List<String> content = new ArrayList<>();
        boolean inside = false;
        boolean found = false;
        for (String line : section) {
            if (FENCE.matcher(line).matches()) {
                if (inside) {
                    inside = false;
                } else {
                    inside = true;
                    found = true;
                    if (!content.isEmpty() && !content.get(content.size() - 1).isBlank()) {
                        content.add("");
                    }
                }
                continue;
            }
            if (inside) {
                content.add(line);
            }
        }
        return found ? content : List.of();
    }

    private String trimBlankLines(List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isBlank()) start++;
        while (end > start && lines.get(end - 1).isBlank()) end--;
        return String.join("\n", lines.subList(start, end)).trim();
    }
}