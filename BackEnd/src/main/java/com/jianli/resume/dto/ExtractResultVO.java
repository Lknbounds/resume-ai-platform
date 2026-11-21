package com.jianli.resume.dto;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  文件上传解析的结果。
 *
 *  ★ 【重要】它不是"创建简历"的结果，而是"提取出的文本 + 质量报告"。
 *    用户看到预览、确认后，才会调 POST /api/resumes 创建简历。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  @param filename   原始文件名（回显用）
 *  @param text       提取出的纯文本（★ 前端填进编辑框，用户可以改）
 *  @param charCount  有效字符数（去掉空白）
 *  @param quality    质量检查结果
 */
public record ExtractResultVO(
        String filename,
        String text,
        int charCount,
        Quality quality
) {

    /**
     * 质量检查结果。
     *
     * 【★★ 为什么要做这个？】
     *
     *  三种文件会提取出"看似有值实际没用"的文本：
     *      扫描件   → 空字符串
     *      中文 CID 字体缺映射 → 一堆 �（替换字符）或方框
     *      内容极短的文件 → 只有几十个字
     *
     *  ★ 如果不管，用户会拿着这些垃圾文本去分析，
     *    得到 0 分或十几分，然后以为"我的简历很差" ——
     *    其实系统根本没读到内容。
     *
     *  ★ ResumeIQ 就是这么干的（extract_text 失败返回空，照样往下走）。
     *    我们要在"提取"和"创建"之间插一道检查。
     *
     * @param level      PASS / WARN / REJECT
     * @param message    给人看的说明（直接显示给用户）
     * @param issues     具体问题列表（可能多条）
     */
    public record Quality(
            String level,
            String message,
            List<String> issues
    ) {
        /** 通过：可以直接用 */
        public boolean passed() {
            return "PASS".equals(level);
        }

        /** 建议：能用但要提醒用户核对 */
        public boolean warning() {
            return "WARN".equals(level);
        }

        /** 拒绝：文本不可用，必须换方式 */
        public boolean rejected() {
            return "REJECT".equals(level);
        }
    }
}
