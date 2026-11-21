package com.jianli.ai;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个异常是什么】
 *  AI 输出"格式不对"或"内容不合理"。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么要单独的异常类型？】
 *
 *  AiCallExecutor 需要区分"网络失败"和"格式失败"：
 *      网络失败 → 直接降级（重试也没用）
 *      格式失败 → 可以用"修复 Prompt"再试一次
 *
 *  ★ 如果都抛 RuntimeException，就无法区分了。
 */
public class AiOutputInvalidException extends RuntimeException {

    public AiOutputInvalidException(String message) {
        super(message);
    }

    public AiOutputInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
