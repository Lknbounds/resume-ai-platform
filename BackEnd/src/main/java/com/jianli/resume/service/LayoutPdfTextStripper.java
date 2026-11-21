package com.jianli.resume.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按 PDF 页面坐标重建阅读顺序的文字提取器。
 *
 * PDFTextStripper 默认按 PDF 内部绘制顺序输出，文本框、分栏和表格会导致乱序。
 * 这里收集每个文字块的 X/Y 坐标，再按页面视觉位置排序。
 */
public class LayoutPdfTextStripper extends PDFTextStripper {

    private final List<TextSpan> spans = new ArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private int pageNo;

    public LayoutPdfTextStripper() throws IOException {
        super();
        setSortByPosition(false);
    }

    public String extract(PDDocument document) throws IOException {
        spans.clear();
        output.setLength(0);
        pageNo = 0;
        setStartPage(1);
        setEndPage(document.getNumberOfPages());
        getText(document);
        return output.toString().strip();
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
        super.startPage(page);
        pageNo++;
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }

        List<TextPosition> ordered = positions.stream()
                .filter(position -> position.getUnicode() != null && !position.getUnicode().isBlank())
                .sorted(Comparator.comparingDouble(TextPosition::getYDirAdj)
                        .thenComparingDouble(TextPosition::getXDirAdj))
                .toList();

        for (List<TextPosition> row : groupRows(ordered)) {
            for (List<TextPosition> chunk : splitColumns(row)) {
                addSpan(chunk);
            }
        }
    }

    private List<List<TextPosition>> groupRows(List<TextPosition> ordered) {
        List<List<TextPosition>> rows = new ArrayList<>();
        for (TextPosition position : ordered) {
            if (rows.isEmpty()) {
                rows.add(new ArrayList<>(List.of(position)));
                continue;
            }

            List<TextPosition> row = rows.get(rows.size() - 1);
            TextPosition previous = row.get(row.size() - 1);
            float tolerance = Math.max(2.5f,
                    Math.max(previous.getFontSizeInPt(), position.getFontSizeInPt()) * 0.38f);
            if (Math.abs(position.getYDirAdj() - previous.getYDirAdj()) <= tolerance) {
                row.add(position);
            } else {
                rows.add(new ArrayList<>(List.of(position)));
            }
        }
        return rows;
    }

    private List<List<TextPosition>> splitColumns(List<TextPosition> row) {
        List<List<TextPosition>> chunks = new ArrayList<>();
        for (TextPosition position : row) {
            if (chunks.isEmpty()) {
                chunks.add(new ArrayList<>(List.of(position)));
                continue;
            }

            List<TextPosition> chunk = chunks.get(chunks.size() - 1);
            TextPosition previous = chunk.get(chunk.size() - 1);
            float gap = position.getXDirAdj()
                    - (previous.getXDirAdj() + previous.getWidthDirAdj());
            float threshold = Math.max(12f,
                    Math.max(previous.getFontSizeInPt(), position.getFontSizeInPt()) * 2.1f);
            if (gap > threshold) {
                chunks.add(new ArrayList<>(List.of(position)));
            } else {
                chunk.add(position);
            }
        }
        return chunks;
    }

    private void addSpan(List<TextPosition> positions) {
        if (positions.isEmpty()) {
            return;
        }

        float minX = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float yTotal = 0;
        float maxHeight = 0;
        float maxFont = 0;
        StringBuilder text = new StringBuilder();
        float previousEnd = Float.NEGATIVE_INFINITY;

        for (TextPosition position : positions) {
            float x = position.getXDirAdj();
            float end = x + position.getWidthDirAdj();
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, end);
            yTotal += position.getYDirAdj();
            maxHeight = Math.max(maxHeight, position.getHeightDir());
            maxFont = Math.max(maxFont, position.getFontSizeInPt());

            if (text.length() > 0) {
                float gap = x - (previousEnd);
                if (gap > Math.max(1.2f, position.getWidthOfSpace() * 0.52f)) {
                    text.append(' ');
                }
            }
            text.append(position.getUnicode());
            previousEnd = end;
        }

        String cleanText = text.toString().strip();
        if (!cleanText.isEmpty()) {
            spans.add(new TextSpan(
                    pageNo,
                    yTotal / positions.size(),
                    minX,
                    maxX,
                    Math.max(maxHeight, maxFont),
                    Math.max(maxFont, 1f),
                    cleanText
            ));
        }
    }
    @Override
    protected void endPage(PDPage page) {
        int currentPage = pageNo;
        List<TextSpan> pageSpans = spans.stream()
                .filter(span -> span.pageNo == currentPage)
                .sorted(Comparator.comparingDouble(TextSpan::y)
                        .thenComparingDouble(TextSpan::x))
                .toList();

        if (output.length() > 0) {
            output.append("\n\n");
        }

        List<VisualLine> lines = groupLines(pageSpans);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                VisualLine previous = lines.get(i - 1);
                VisualLine current = lines.get(i);
                float gap = current.y - previous.y;
                float threshold = Math.max(11f, Math.max(previous.maxFont, current.maxFont) * 1.9f);
                output.append(gap > threshold ? "\n\n" : "\n");
            }
            output.append(renderLine(lines.get(i)));
        }
    }

    private List<VisualLine> groupLines(List<TextSpan> sorted) {
        List<VisualLine> lines = new ArrayList<>();
        for (TextSpan span : sorted) {
            if (lines.isEmpty()) {
                lines.add(new VisualLine(span));
                continue;
            }

            VisualLine line = lines.get(lines.size() - 1);
            float tolerance = Math.max(2.5f, Math.max(line.maxFont, span.maxFont) * 0.38f);
            if (Math.abs(span.y - line.y) <= tolerance) {
                line.add(span);
            } else {
                lines.add(new VisualLine(span));
            }
        }
        lines.forEach(VisualLine::finish);
        return lines;
    }

    private String renderLine(VisualLine line) {
        StringBuilder result = new StringBuilder();
        TextSpan previous = null;

        for (TextSpan span : line.spans) {
            if (previous != null) {
                float gap = span.x - previous.endX;
                float separatorThreshold = Math.max(12f, Math.max(previous.maxFont, span.maxFont) * 2.1f);
                if (gap > separatorThreshold) {
                    result.append('\t');
                } else if (gap > Math.max(1.2f, previous.maxFont * 0.28f)
                        && !endsWithSpace(result)
                        && !span.text.startsWith(" ")
                        && !isCjk(lastChar(result))
                        && !isCjk(firstChar(span.text))) {
                    result.append(' ');
                }
            }
            result.append(span.text);
            previous = span;
        }

        return result.toString().strip();
    }

    private boolean endsWithSpace(StringBuilder value) {
        return value.length() > 0 && value.charAt(value.length() - 1) == ' ';
    }

    private char lastChar(StringBuilder value) {
        return value.length() == 0 ? '\0' : value.charAt(value.length() - 1);
    }

    private char firstChar(String value) {
        return value.isEmpty() ? '\0' : value.charAt(0);
    }

    private boolean isCjk(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private static final class VisualLine {
        private final List<TextSpan> spans = new ArrayList<>();
        private float y;
        private float maxFont;

        private VisualLine(TextSpan span) {
            add(span);
        }

        private void add(TextSpan span) {
            spans.add(span);
            y = (float) spans.stream().mapToDouble(TextSpan::y).average().orElse(span.y);
            maxFont = Math.max(maxFont, span.maxFont);
        }

        private void finish() {
            spans.sort(Comparator.comparingDouble(TextSpan::x));
        }
    }

    private record TextSpan(
            int pageNo,
            float y,
            float x,
            float endX,
            float height,
            float maxFont,
            String text
    ) {
    }
}
