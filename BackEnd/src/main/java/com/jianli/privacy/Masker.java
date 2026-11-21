package com.jianli.privacy;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  脱敏工具。把命中的敏感信息变成"看得出类型但看不出内容"的形式。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么必须脱敏？】
 *
 *  返回给前端的错误提示里如果带完整手机号：
 *      拦截器出错 → 日志记录 → 日志里有完整手机号
 *      用户截图提问 → 完整手机号进了聊天记录
 *
 *  "为了防止泄露，我把手机号告诉了你"——这是荒谬的。
 *
 *  ★ 所以规则是：所有返回给前端的片段，一律脱敏。
 *    用户自己知道原文是什么（就在他输入框里），
 *    但他需要一个"位置"来定位 —— start/end 提供这个。
 */
public final class Masker {

    private Masker() {
        // 工具类，禁止实例化
    }

    /**
     * 按类型脱敏。
     *
     * ★ 每种类型的脱敏规则不同，共同点是"保留足够辨认的字符"：
     *    手机号保留前 3 后 4（能认出是不是自己的号）
     *    身份证保留前 4 后 4
     *    邮箱保留前 2 + 域名
     *    ★ 这样用户能确认"对，这是我的信息"，但旁人看不全。
     */
    public static String mask(SensitiveType type, String value) {
        if (value == null || value.isEmpty()) {
            return "***";
        }

        return switch (type) {
            case PHONE -> value.length() >= 7
                    ? value.substring(0, 3) + "****" + value.substring(value.length() - 4)
                    : "***";

            case ID_CARD -> value.length() >= 8
                    ? value.substring(0, 4) + "**********" + value.substring(value.length() - 4)
                    : "***";

            case EMAIL -> {
                int at = value.indexOf('@');
                yield at > 2 ? value.substring(0, 2) + "***" + value.substring(at) : "***";
            }

            case ADDRESS -> value.length() > 6
                    ? value.substring(0, 6) + "***"
                    : "***";

            case BANK_CARD -> value.length() >= 4
                    ? "**** **** **** " + value.substring(value.length() - 4)
                    : "****";

            case SOCIAL_ACCOUNT -> value.length() > 2
                    ? value.substring(0, 2) + "***"
                    : "***";
        };
    }
}
