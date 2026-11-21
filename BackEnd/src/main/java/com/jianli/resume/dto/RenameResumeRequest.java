package com.jianli.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  修改简历信息的请求体。
 *
 *      PUT /api/resumes/10
 *      {"title":"Java 后端简历（社招版）","targetPosition":"Java 后端开发工程师"}
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么只有标题和岗位，没有正文？】
 *
 *  因为正文不能通过这个接口改 —— 改正文必须走"新增版本"。
 *  ★ 这是"内容不可变"原则在接口层面的体现：
 *    没有任何接口能 UPDATE resume_version.content。
 *
 *  如果允许在这里改正文，就绕过了版本机制：
 *    - 历史丢失
 *    - 版本对比失效
 *    - 分析报告的证据找不到
 *
 *  ★ 好的 API 设计不只是"能完成任务"，
 *    还要"让错误的用法表达不出来"。
 *
 *  【★ 这个接口承担两个职责，为什么不拆成两个接口？】
 *
 *    改标题 / 改目标岗位，本质上都是"改简历的元信息"。
 *    拆成 PUT /resumes/{id}/title 和 PUT /resumes/{id}/position 会让
 *    前端多两个调用，而且用户通常是一次改完。
 *
 *    放在一起，前端提交整份表单即可。
 *    ★ 如果将来元信息字段变多（标签、备注），也不建议拆 ——
 *      一个"编辑简历信息"接口够了。
 */
public record RenameResumeRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 64, message = "标题最长 64 个字符")
        String title,

        /**
         * 目标岗位。★ 可为空（表示清除）。
         *
         * ★ 空字符串和 null 都当作"清空"处理。
         *   用户想删掉岗位时，前端会传 null 或空串。
         */
        @Size(max = 64, message = "目标岗位最长 64 个字符")
        String targetPosition
) {}
