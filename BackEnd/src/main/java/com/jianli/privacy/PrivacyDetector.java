package com.jianli.privacy;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  个人信息检测器。★ 纯规则、纯内存、无任何外部依赖。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么用规则而不是 AI？（面试必问）】
 *
 *  ① 确定性
 *     手机号就是 11 位 1[3-9] 开头。规则给的是"是/否"，
 *     不会今天判命中明天不判。发布审核是二元决策，不能模棱两可。
 *
 *  ② 可审计
 *     审核记录要落库（"规则 v1、PHONE、偏移 132-143"）——
 *     这是可复核的事实。而"AI 觉得像手机号"无法复核。
 *
 *  ③ 延迟
 *     规则 1-3ms，AI 2-10s。用户点"发布"后要立刻反馈。
 *
 *  ④ ★ 隐私自洽（最关键）
 *     审核的目的就是"不要把个人信息发出去"。
 *     如果审核过程把含身份证的原文发给第三方大模型 ——
 *     数据已经出网了，逻辑上自相矛盾。
 *
 *  ⑤ 可测试
 *     每条规则都能写正反例单测。AI 写不了断言。
 *
 *  ★ 一句话：规则负责确定性，AI 只能锦上添花（且默认关闭）。
 *
 *
 *  【★★ 为什么每条规则都要"二次校验"？】
 *
 *  纯正则误报率太高：
 *      18 位数字不一定是身份证（订单号、项目编号）
 *      16-19 位数字不一定是银行卡
 *      有"市/区"不一定是住址（"我在杭州工作"）
 *
 *  所以加校验：
 *      身份证 → ISO 7064 MOD 11-2 校验位 + 出生日期合法性
 *      银行卡 → Luhn 校验
 *      住址   → 必须"行政区划链 且 门牌号"两个条件同时满足
 *
 *  ★ 这是把误报率从"每天几十个用户被无端拒绝"降到接近零的关键。
 */
@Component
public class PrivacyDetector {

    /** 规则版本。规则改动【必须】升版本 —— 审核记录里要能追溯当时用的是哪版规则 */
    public static final String RULE_VERSION = "v1";

    // ═══════════════════════════════════════════════════════════════
    //  正则
    // ═══════════════════════════════════════════════════════════════

    /**
     * 手机号：11 位，1 开头，第二位 3-9。
     *
     * ★ (?<!\d) 和 (?!\d) 是前后断言：
     *   防止从更长的数字串里截取出"11 位"（比如 18 位订单号里的后 11 位）。
     */
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(?:\\+?86)?1[3-9]\\d{9}(?!\\d)");

    /** 身份证：17 位数字 + 1 位数字或 X */
    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    /** 邮箱 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** 银行卡：16-19 位连续数字 */
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    /** 社交账号：微信/QQ + 可选冒号 + 5-20 位账号 */
    private static final Pattern SOCIAL =
            Pattern.compile("(?:微信|wechat|vx|v信|QQ)\\s*[:：]?\\s*[A-Za-z0-9_\\-]{5,20}",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 住址：两段式（行政区划链 + 门牌号）。
     *
     * 【★ 为什么要两段式？】
     *
     *  只匹配"杭州市西湖区"会误报 —— 用户可能只是说"我在杭州工作"。
     *  只匹配"123 号"会误报 —— 可能是"订单 123 号"。
     *
     *  ★ 必须同时有"行政区划链"和"门牌号"，才判定为住址。
     *
     *  正例：杭州市西湖区文三路 123 号 5 幢 302 室   ✓
     *  反例：杭州市                                 ✗（没有门牌）
     *  反例：我在杭州工作了 3 年                      ✗（没有门牌）
     */
    private static final String ADMIN_CHAIN =
            "(?:[\\u4e00-\\u9fa5]{2,8}(?:省|自治区|特别行政区|市|区|县|镇|乡|街道)){1,6}";

    private static final String DOOR =
            "[^，。；;、\\n]{0,40}?(?:路|街|巷|弄|道|小区|花园|公寓|大厦)\\s*\\d{1,5}\\s*(?:号|弄)"
                    + "[^，。；;\\n]{0,20}?(?:\\d{1,5}\\s*(?:栋|幢|单元|室|楼))?";

    private static final Pattern ADDRESS = Pattern.compile(ADMIN_CHAIN + DOOR);

    // ═══════════════════════════════════════════════════════════════
    //  身份证校验位常量
    // ═══════════════════════════════════════════════════════════════

    /** 加权因子（前 17 位） */
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 校验码对照表（模 11 的结果 → 校验位） */
    private static final char[] ID_CHECK = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    // ═══════════════════════════════════════════════════════════════
    //  规则表
    // ═══════════════════════════════════════════════════════════════

    /** 一条规则：类型 + 正则 + 可选二次校验 */
    private record Rule(SensitiveType type, Pattern pattern, Predicate<String> validator) {}

    /**
     * ★ 规则集合用声明式配置。
     *
     *   加一种新类型只需要在这里加一行 —— 不改任何遍历逻辑。
     *   ★ 遍历顺序固定，保证输出稳定（便于测试断言）。
     */
    private static final List<Rule> RULES = List.of(
            new Rule(SensitiveType.PHONE, PHONE, null),
            new Rule(SensitiveType.ID_CARD, ID_CARD, PrivacyDetector::validIdCard),
            new Rule(SensitiveType.EMAIL, EMAIL, null),
            new Rule(SensitiveType.ADDRESS, ADDRESS, null),
            new Rule(SensitiveType.BANK_CARD, BANK_CARD, PrivacyDetector::luhn),
            new Rule(SensitiveType.SOCIAL_ACCOUNT, SOCIAL, null)
    );

    // ═══════════════════════════════════════════════════════════════
    //  主入口
    // ═══════════════════════════════════════════════════════════════

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【扫描文本，找出所有敏感信息】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param raw 原文（用户在输入框里看到的）
     * @return 命中的敏感项，按在原文中的位置排序
     */
    public List<PrivacyHit> scan(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        NormalizedText nt = NormalizedText.of(raw);
        List<PrivacyHit> hits = new ArrayList<>();

        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(nt.text());

            while (m.find()) {
                String value = m.group();

                // ★ 二次校验：身份证校验位、银行卡 Luhn
                if (rule.validator() != null && !rule.validator().test(value)) {
                    continue;
                }

                // ★ 归一化区间 → 原文区间（用于前端高亮）
                int[] range = nt.toOriginalRange(m.start(), m.end());

                // 从原文取片段（保证脱敏用的是用户真实写的形式）
                String origin = raw.substring(range[0], range[1]);

                hits.add(new PrivacyHit(
                        rule.type(),
                        range[0],
                        range[1],
                        Masker.mask(rule.type(), origin),
                        rule.type().advice()
                ));
            }
        }

        return mergeOverlaps(hits);
    }

    /** 快速判断：有敏感信息吗 */
    public boolean passed(String raw) {
        return scan(raw).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    //  二次校验
    // ═══════════════════════════════════════════════════════════════

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【身份证校验】ISO 7064 MOD 11-2
     * ═══════════════════════════════════════════════════════════════
     *
     * 算法：
     *   前 17 位各乘以加权因子，求和
     *   和对 11 取模，用对照表查出应该的校验位
     *   和第 18 位比对
     *
     * ★ 为什么必须做这个？
     *   不做的话，订单号 "202311010000000001" 这种 18 位数字
     *   会被误判成身份证，用户被无端拒绝发布。
     *   加校验位后误报率降到几乎为零 —— 随机 18 位数字通过校验的概率只有 1/11。
     *
     * ★ 还要校验出生日期：
     *   11010519491231002X 里的 19491231 必须是合法日期。
     *   加这一层能再拦掉一批"瞎编的号码"。
     */
    static boolean validIdCard(String id) {
        if (id == null || id.length() != 18) {
            return false;
        }

        // ───── 校验位 ─────
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = id.charAt(i);
            if (!Character.isDigit(c)) return false;
            sum += (c - '0') * ID_WEIGHTS[i];
        }

        char expected = ID_CHECK[sum % 11];
        if (Character.toUpperCase(id.charAt(17)) != expected) {
            return false;
        }

        // ───── 出生日期合法性 ─────
        String birthday = id.substring(6, 14);   // yyyyMMdd
        try {
            LocalDate d = LocalDate.parse(birthday, DateTimeFormatter.BASIC_ISO_DATE);
            return d.getYear() >= 1900 && d.getYear() <= LocalDate.now().getYear();
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【Luhn 校验】银行卡号
     * ═══════════════════════════════════════════════════════════════
     *
     * 算法：
     *   从右往左，每隔一位数字乘 2
     *   乘积 > 9 就减 9
     *   全部相加，能被 10 整除则合法
     *
     * ★ 这是银行卡号的通用校验算法。
     *   加上它，16-19 位的订单号、流水号就不会被误判成银行卡。
     */
    static boolean luhn(String digits) {
        if (digits == null || digits.length() < 16) {
            return false;
        }

        int sum = 0;
        boolean doubleIt = false;

        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }

        return sum % 10 == 0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  重叠合并
    // ═══════════════════════════════════════════════════════════════

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【合并重叠命中】
     * ═══════════════════════════════════════════════════════════════
     *
     * 【★ 为什么会有重叠？】
     *
     *  一个 18 位身份证号，也会被"银行卡 16-19 位"规则匹配到。
     *  如果两条都返回，前端会高亮两次、用户看到两条一样的提示。
     *
     *  ★ 保留原则：区间更长的那条。
     *    因为范围更大的检测通常更精确（身份证规则有校验位，比银行卡规则更可靠）。
     *
     * ★ 为什么要先排序？
     *   合并算法是"跟上一个比较"，必须按起始位置排序才能正确工作。
     *   位置相同时，长的排前面（这样短的会被判定为重叠并被跳过）。
     */
    private List<PrivacyHit> mergeOverlaps(List<PrivacyHit> hits) {
        List<PrivacyHit> sorted = hits.stream()
                .sorted(Comparator.comparingInt(PrivacyHit::start)
                        .thenComparing(h -> -(h.end() - h.start())))
                .toList();

        List<PrivacyHit> out = new ArrayList<>();

        for (PrivacyHit h : sorted) {
            if (!out.isEmpty()) {
                PrivacyHit last = out.get(out.size() - 1);

                // 有重叠
                if (h.start() < last.end()) {
                    // 新的更长 → 替换
                    if ((h.end() - h.start()) > (last.end() - last.start())) {
                        out.set(out.size() - 1, h);
                    }
                    continue;   // 否则跳过这条
                }
            }
            out.add(h);
        }

        return List.copyOf(out);
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 性能】为什么这个类不用 @Component 之外的东西？
     *
     *  它是纯规则的，没有状态、没有依赖、没有 IO。
     *  一万字的文本扫描约 1-3ms。
     *
     *  ★ 正因如此，它可以直接 new 出来写单测 ——
     *    不需要 Spring 容器、不需要数据库。
     *    这是"纯函数类"最大的价值：可测、可复用、不会拖慢启动。
     * ═══════════════════════════════════════════════════════════════
     */
}
