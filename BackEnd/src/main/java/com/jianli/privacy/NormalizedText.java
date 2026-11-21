package com.jianli.privacy;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  归一化文本 + 下标映射。★ 这是"高亮定位"能工作的关键。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么需要它？】
 *
 *  用户在简历里写的手机号，可能是这几种形式：
 *      13800138000
 *      138-0013-8000
 *      138 0013 8000
 *      +86 138 0013 8000
 *      １３８００１３８０００      ← 全角
 *
 *  直接对原文跑正则 `1[3-9]\d{9}`，第二种和第四、五种都匹配不上。
 *
 *  ★ 解法：先归一化（全角转半角、吞掉数字间的分隔符），在归一化文本上匹配。
 *
 *  ★ 但这就带来新问题：
 *      归一化文本里"13800138000"的起始下标是 0，
 *      而原文里它可能是从下标 132 开始的（前面还有别的字）。
 *      而且因为吞掉了短横线，两个下标系完全对不上。
 *
 *  ★ 所以必须维护一个映射数组：
 *      indexMap[归一化下标] = 原文下标
 *      匹配到归一化文本的 [s, e) 后，
 *      用 indexMap[s] 和 indexMap[e-1]+1 换回原文区间。
 *
 *  这样前端拿到 start/end 就能精确高亮原文里的那一段。
 *
 *
 *  【★ 数据流向】
 *
 *      用户输入原文 "电话 138-0013-8000"
 *         ↓
 *      normalize() → 归一化文本 "电话 13800138000" + 映射表
 *         ↓
 *      正则匹配 → 归一化区间 [3, 14)
 *         ↓
 *      toOriginalRange() → 原文区间 [3, 16)
 *         ↓
 *      前端 textarea.setSelectionRange(3, 16) → 精确选中
 */
public record NormalizedText(
        String text,
        int[] indexMap,
        String raw
) {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【归一化】
     * ═══════════════════════════════════════════════════════════════
     *
     * 做两件事：
     *   1. 全角字符 → 半角（数字、字母、@、.、-）
     *   2. 吞掉【数字之间】的空格和短横线
     *
     * ★ 为什么只吞"数字之间"的？
     *   "电话 138-0013-8000" 里的空格和短横要吞；
     *   但 "张三 13800138000" 里的空格不能吞（它是词语分隔）。
     *   所以判断条件是"前一个字符是数字 且 后一个字符是数字"。
     */
    public static NormalizedText of(String raw) {
        String src = raw == null ? "" : raw;

        StringBuilder sb = new StringBuilder(src.length());
        // ★ +1 是因为要能表示"末尾之后"的位置
        int[] map = new int[src.length() + 1];

        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = toHalfWidth(c);

            boolean isSep = (n == ' ' || n == '-' || n == '　');

            /*
             * ★ 判断是否吞掉：
             *   - 是分隔符
             *   - 已经写过至少一个字符
             *   - 前一个字符是数字
             *   - 后一个字符（原始）也是数字
             */
            if (isSep && sb.length() > 0 && i + 1 < src.length()
                    && Character.isDigit(sb.charAt(sb.length() - 1))
                    && Character.isDigit(toHalfWidth(src.charAt(i + 1)))) {
                continue;   // 跳过这个字符，不写入、不记录映射
            }

            map[sb.length()] = i;
            sb.append(n);
        }

        /*
         * ★★ 最后一位必须单独设置！
         *
         *   它是"归一化文本长度"对应到"原文字符串末尾"。
         *   匹配到文本末尾时（比如手机号在最后），
         *   toOriginalRange 会用到 map[text.length()]。
         *
         *   ★ 不设的话这个位置是 0，会导致 end 算成 0 → 区间无效。
         */
        map[sb.length()] = src.length();

        return new NormalizedText(sb.toString(), map, src);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【归一化区间 → 原文区间】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param start 归一化文本中的起始下标（含）
     * @param end   归一化文本中的结束下标（不含）
     * @return [原文起始, 原文结束)
     *
     * ★ 为什么 end 用 map[end-1] + 1 而不是 map[end]？
     *   因为 map 存的是"每个归一化字符对应的原文起点"。
     *   end 是"最后一个字符之后"的位置，用 map[end] 会多算一个字符。
     *
     *   ★ 例外：如果 end == text.length()（匹配到末尾），
     *     map[end] 已经被设成 raw.length()，这时候用 map[end] 是对的。
     *     下面的 Math.min 处理了这个边界。
     */
    public int[] toOriginalRange(int start, int end) {
        if (indexMap.length == 0) return new int[]{0, 0};

        int s = indexMap[Math.min(start, indexMap.length - 1)];

        int e;
        if (end <= 0) {
            e = s + 1;
        } else if (end >= indexMap.length) {
            e = raw.length();
        } else {
            // ★ 用 end-1 的映射 +1，才是"最后一个字符之后"
            e = indexMap[end - 1] + 1;
        }

        // 兜底：保证 end > start
        return new int[]{s, Math.max(e, s + 1)};
    }

    /**
     * 全角 → 半角。
     *
     * ★ 中文输入法很容易打出全角数字和符号（１３８００１３８０００）。
     *   不转换的话正则一个都匹配不上。
     */
    private static char toHalfWidth(char c) {
        if (c == '　') return ' ';                          // 全角空格
        if (c >= '０' && c <= '９') return (char) (c - '０' + '0'); // 全角数字
        if (c >= 'Ａ' && c <= 'Ｚ') return (char) (c - 'Ａ' + 'A');
        if (c >= 'ａ' && c <= 'ｚ') return (char) (c - 'ａ' + 'a');
        if (c == '＠') return '@';
        if (c == '．') return '.';
        if (c == '－') return '-';
        if (c == 'Ｘ' || c == 'ｘ') return 'X';
        return c;
    }
}
