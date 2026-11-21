package com.jianli.resume.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  简历文本的"清洗工"。负责三件事：
 *      1. 归一化（统一换行、去 BOM、压缩空行）
 *      2. 算字符数（去掉空白）
 *      3. 算 SHA-256 哈希
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么这个类被单独拆出来？】
 *
 *  因为它会被【多个模块】用到：
 *      resume  → 创建版本时归一化 + 算 hash
 *      jd      → JD 内容也要归一化 + 算 hash（步骤 7）
 *      privacy → 审核前也要算 content_hash（步骤 12，不存原文）
 *
 *  ★ 如果每个模块各写一份 normalize，就会出现：
 *      "简历的归一化去掉了 BOM，但 JD 的没去"
 *      "两处压缩空行的规则不一样"
 *    → 同样的文本算出不同的 hash → 缓存穿透、去重失效。
 *
 *  ★ 抽成一个 @Component，一处修改处处生效。
 *
 *
 *  【★★ 为什么必须归一化？如果不做会怎样？】
 *
 *  场景一：用户从 Word 复制简历
 *      带过来的是 \r\n（Windows 换行）。
 *      另一个用户从 Mac 复制，是 \n。
 *      内容看起来一样，但 hash 完全不同 ——
 *      缓存失效、去重失效。
 *
 *  场景二：文件开头有个不可见的 BOM 字符（﻿）
 *      编辑器自动加的，用户看不见。
 *      不处理的话，它会被当成简历的第一个字，
 *      影响"至少 50 字"的判断，也可能让 AI 解析时分错段落。
 *
 *  场景三：连续空行
 *      用户删了一整段，留下 5 个连续空行。
 *      看起来没内容，但占字符、占 token、diff 时全是噪音。
 *
 *  ★ 归一化之后，同样的"语义内容"永远得到同样的字节序列，
 *    hash 才稳定，缓存才有效。
 *
 *
 *  【★ 为什么用 @Component 而不是工具类（全 static）？】
 *
 *  技术上完全可以写成 static 工具类。用 @Component 的理由：
 *      1. 以后可能需要注入配置（比如"最多保留几个空行"）
 *      2. 单测时可以注入，更容易 mock
 *      3. 和项目里其他服务的写法一致
 *
 *  ★ 但其实它【无状态、无依赖】，所以直接 new 也能测：
 *      new ResumeTextNormalizer().normalize("...")
 *     这在写单测时很方便，不需要起 Spring 容器。
 */
@Component
public class ResumeTextNormalizer {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【归一化】把用户粘贴的文本清洗成标准形式
     * ═══════════════════════════════════════════════════════════════
     *
     * @param raw 用户粘贴的原始文本（可能为 null）
     * @return 归一化后的文本，永远不为 null
     */
    public String normalize(String raw) {
        // ★ 判空返回空串而不是 null —— 让调用方少一层判断
        if (raw == null) {
            return "";
        }

        /*
         * 【第一步：统一换行符 + 去 BOM】
         *
         * "﻿" 是 BOM（Byte Order Mark），也就是那个看不见的字符。
         * 用 Unicode 转义写，而不是直接粘一个不可见字符进来 ——
         * 后者在代码里根本看不见，维护的人会一头雾水。
         *
         * ★ 顺序很重要：先处理 \r\n（两字符），再处理 \r（单字符）。
         *   如果反过来先把 \r 换成 \n，\r\n 就变成了 \n\n（多一个空行）。
         */
        String t = raw
                .replace("﻿", "")      // BOM
                .replace("\r\n", "\n")      // Windows 换行
                .replace('\r', '\n');       // 老 Mac 换行

        /*
         * 【第二步：逐行处理 —— 去行尾空白 + 压缩连续空行】
         *
         * ★ 为什么用 split("\n", -1) 而不是 split("\n")？
         *   带 -1 表示"保留末尾的空字符串"。
         *   不带的话，末尾的换行会被吃掉，行数统计不准。
         *
         *   ★ 这个 -1 参数是个很常见的坑：
         *     "a\nb\n".split("\n")     → ["a", "b"]      （长度 2）
         *     "a\nb\n".split("\n", -1) → ["a", "b", ""]  （长度 3）
         */
        String[] lines = t.split("\n", -1);

        StringBuilder sb = new StringBuilder(t.length());

        /*
         * blankRun 记录"当前连续遇到第几个空行"。
         * 超过 2 个就不再追加 —— 也就是最多保留 2 个连续空行。
         *
         * ★ 为什么最多保留 2 个而不是 1 个？
         *   2 个空行在视觉上是"段落分隔"，1 个只是"换行"。
         *   保持段落感，但压掉用户误按的空行。
         */
        int blankRun = 0;

        for (String line : lines) {
            /*
             * 去掉行尾空白（空格、Tab、全角空格）。
             *
             * ★ 为什么用正则 "[ \t　]+$" 而不是 trim()？
             *   trim() 会同时去掉【行首】缩进 ——
             *   而简历里的缩进是有意义的（列表项、层级）。
             *   我们只想清掉行尾那些看不见的尾巴。
             *
             * ★ "　" 是全角空格（U+3000），中文输入法很容易打出来。
             *   它和半角空格在视觉上差不多，但不处理的话会算进字符数。
             */
            String trimmed = line.replaceAll("[ \\t\\u3000]+$", "");

            if (trimmed.isBlank()) {
                /*
                 * 空行。
                 * ★ 注意：isBlank() 判断的是"全是空白字符"，
                 *   比 isEmpty() 更准确（一个只有空格的"空行"其实不空）。
                 */
                blankRun++;
                if (blankRun > 2) {
                    continue;   // 超过 2 个连续空行，跳过
                }
                sb.append('\n');
            } else {
                blankRun = 0;   // 遇到非空行，重置计数
                sb.append(trimmed).append('\n');
            }
        }

        /*
         * 【第三步：去掉首尾空白】
         *
         * strip() 比 trim() 更彻底：
         *   trim()  只去掉 ASCII 定义的空白（<= U+0020）
         *   strip() 去掉所有 Unicode 空白（包括全角空格、各种 Unicode 空格）
         *
         * ★ Java 11+ 才有的方法，我们用的是 17，可以用。
         */
        return sb.toString().strip();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【字符数】去掉所有空白后计数
     * ═══════════════════════════════════════════════════════════════
     *
     * @param normalized 归一化后的文本
     * @return 有效字符数
     *
     * 【★ 为什么去掉空白再算？】
     *
     *  用户理解的"50 字"是"我写了 50 个实际内容"，
     *  而不是"50 个字符（含换行和缩进）"。
     *
     *  反例：一份只有 10 个字、但每行缩进 4 空格的简历，
     *       算上空白可能有 60 多 —— 会被误判为"足够长"。
     *
     *  ★ 所以校验"至少 50 字"时，用这个口径。
     *    这也和前端提示的"已输入 XX 字"保持一致
     *    （前端也是去掉空白再算的）。
     *
     * ★ replaceAll("\\s", "") 会去掉所有的空白字符，
     *   包括空格、Tab、换行、全角空格（\s 在 Java 正则里默认包含 Unicode 空白吗？）
     *
     *   ★ 注意：Java 的 \s 默认【不包含】全角空格 U+3000。
     *     但我们在 normalize 里已经处理过行尾的全角空格了，
     *     中间的保留也没关系（它们是"内容的一部分"）。
     */
    public int charCount(String normalized) {
        if (normalized == null) {
            return 0;
        }
        return normalized.replaceAll("\\s", "").length();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【SHA-256 哈希】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param text 要哈希的文本
     * @return 64 位小写十六进制字符串
     *
     * 【★ 为什么用 SHA-256 而不是 MD5？】
     *
     *   两者在这里都不涉及安全（只是做去重和缓存 key），
     *   但 SHA-256 有两个优势：
     *     1. 碰撞概率极低（MD5 已有实际碰撞案例）
     *     2. 输出固定 64 字符，正好对应 CHAR(64)
     *
     *   ★ MD5 只有 128 位，理论上两份不同的简历可能算出同一个值
     *     —— 虽然概率极低，但"两份简历被认为是同一份"是个很难解释的 bug。
     *
     * 【★ HexFormat 是什么？】
     *   Java 17 新增的工具类，字节数组 → 十六进制字符串一行搞定。
     *   以前要自己写循环 + String.format("%02x", b) —— 又慢又丑。
     *
     * 【★ 为什么要用 UTF-8 编码？】
     *   getBytes() 不指定字符集时用平台默认编码。
     *   Windows 默认是 GBK，Linux 默认是 UTF-8 ——
     *   同一份中文简历在两台机器上会算出【不同的 hash】！
     *
     *   ★ 这是个很隐蔽的坑：本地开发（Windows）和服务器（Linux）
     *     算出的 hash 不一致 → 缓存永不命中 → 用户每次都重新解析。
     *
     *   显式指定 UTF_8 就避免了这个问题。
     */
    public String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            /*
             * ★ 这里传的是归一化后的文本。调用方必须保证先 normalize。
             *   如果传原始文本，同样的内容可能算出不同 hash。
             *
             *   ★ 更严谨的做法是在本方法内部也 normalize 一次，
             *     但那样等于每次调用都归一化两遍（浪费）。
             *     所以约定：调用方先 normalize，再 hash。
             */
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            /*
             * ★ SHA-256 是 Java 标准库必须支持的算法，
             *   走到这里说明 JVM 环境有问题（极不可能）。
             *
             *   抛 IllegalStateException 而不是 RuntimeException：
             *   它表示"程序状态不对"，不是业务错误。
             */
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
