package com.jianli.resume.service;

import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  "查简历版本的最新分数"。定义在 resume 包，由 analysis 包实现。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么要定一个接口，而不是直接依赖 AnalysisService？】
 *
 *  依赖方向问题：
 *      analysis 包 依赖 resume 包（AnalysisService 要用 ResumeService 校验版本归属）
 *      如果 resume 包再直接依赖 analysis 包 → 包级循环依赖
 *
 *  ★ 虽然 Spring 的 Bean 依赖不成环（一个是 Service、一个是 Repository），
 *    但【包与包互相 import】在架构上是坏味道：
 *      改 analysis 包可能影响到 resume 包，两个模块再也拆不开。
 *
 *  ★ 用接口倒置依赖：
 *      resume 包定义一个"我需要什么"的接口（本文件）
 *      analysis 包提供实现
 *      resume 包只依赖接口，不知道谁实现的
 *
 *  这叫做"依赖倒置原则"（DIP）—— 上层不依赖下层，都依赖抽象。
 *  ★ 面试如果问"你的模块怎么解耦的"，这就是答案。
 *
 *  【★ 为什么不干脆不要这个功能？】
 *    简历列表要显示分数，这是用户明确要的
 *    （"一眼看出每份简历多少分"）。
 *    不做的话，用户得一个个点进去看。
 */
public interface ResumeScoreLookup {

    /**
     * 批量查多个简历版本的最新总分。
     *
     * @param userId     当前用户（用于越权过滤）
     * @param versionIds 要查的版本 id 列表
     * @return versionId → 总分。没有分析记录的版本不在 map 里
     *
     * ★ 返回 Map 而不是 List：调用方要按 versionId 匹配，
     *   Map 的 get 比 List 的 stream 查找更直接。
     *
     * ★ 没有分析记录的版本【不放进 map】而不是放 0 分 ——
     *   "没分析过"和"考了 0 分"是两回事，
     *   前端要能区分（显示"—" vs 显示"0"）。
     */
    Map<Long, Double> latestScores(Long userId, List<Long> versionIds);
}
