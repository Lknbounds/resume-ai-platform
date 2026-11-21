package com.jianli.ai;

import com.jianli.ai.model.InterviewQ;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  AI 能力的【唯一出口】。全项目所有 AI 调用都从这里走。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么要定义一个接口，而不是直接注入 AiGatewayImpl？】
 *
 *  ① 防止绕过开关
 *     业务代码只依赖接口，实现类注入的 ChatClient 不对外暴露。
 *     这样"S 某处偷偷调 AI"在架构上就做不到。
 *
 *  ② 便于测试
 *     单测可以 mock 这个接口，返回固定的 InterviewQ 列表，
 *     不需要真的调模型。
 *
 *  ③ 便于扩展
 *     将来接第二家模型，只加一个实现，业务代码零改动。
 *
 *  【★ 返回值为什么是 AiResult<List<InterviewQ>> 而不是 List<InterviewQ>？】
 *
 *  因为调用方需要知道：
 *      - 这批题是 AI 生成的还是模板生成的（source）
 *      - 是否降级了（degraded + reason）
 *
 *  ★ 只有数据没有来源，前端就无法诚实地告诉用户"这是模板生成的"。
 */
public interface AiGateway {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【生成面试预演题】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param position      目标岗位名
     * @param jdSkills      JD 要求的技能（名称列表）
     * @param projects      候选人的项目（已格式化的文本，每行一个）
     * @param experiences   候选人的经历（已格式化的文本）
     * @param resumeSkills  候选人的技能（名称列表）
     * @param count         要生成几道
     * @return AiResult 包装的题目列表
     *
     * ★ 注意：这个方法【不抛业务异常】。
     *   AI 失败时返回降级的模板题，而不是抛异常。
     *   ★ 为什么？
     *     面试题是可选功能，生成失败不该让整个请求失败 ——
     *     给用户模板题，比给用户一个错误页面好。
     *
     *   ★ 只有"连降级都失败"才会抛 IllegalStateException。
     */
    AiResult<List<InterviewQ>> generateInterviewQuestions(
            String position,
            List<String> jdSkills,
            List<String> projects,
            List<String> experiences,
            List<String> resumeSkills,
            int count
    );
}
