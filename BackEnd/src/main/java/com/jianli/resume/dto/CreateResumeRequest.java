package com.jianli.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  新建简历的请求体。
 *
 *      POST /api/resumes
 *      {"title":"Java 后端简历","content":"张三 | 后端开发\n教育背景：..."}
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      前端弹窗表单（标题 + 粘贴的正文）
 *         ↓
 *      POST /api/resumes
 *         ↓
 *      ResumeController.create(@CurrentUserId Long userId,
 *                              @RequestBody @Valid CreateResumeRequest req)
 *         ├─ Jackson 反序列化
 *         └─ @Valid 校验（两个字段都非空）
 *         ↓
 *      ResumeService.create(userId, req)
 *         ├─ 归一化 + 校验字数（≥50）
 *         ├─ INSERT resume
 *         ├─ INSERT resume_version (v1)
 *         └─ UPDATE resume.current_version_id
 *         ↓
 *      返回 ResumeDetailVO（含 id、版本列表）
 *         ↓
 *      前端拿到 id 后跳转到 /resumes/{id}
 */
public record CreateResumeRequest(

        /**
         * 简历标题。
         *
         * ★ @Size(max = 64) 对应数据库的 VARCHAR(64)。
         *   必须一致，否则超长会插入失败（报 500 而不是友好的 400）。
         */
        @NotBlank(message = "请填写简历标题")
        @Size(max = 64, message = "标题最长 64 个字符")
        String title,

        /**
         * 目标岗位。★ 选填。
         *
         * 【★ 为什么选填而不是必填？】
         *
         *  用户可能：
         *    1. 先存一份简历，还没想好投什么
         *    2. 用同一份简历投多个岗位，不想绑死
         *
         *  ★ 强制填会挡住"我就想先存一下"的用户。
         *    但我们会在前端提示"填写目标岗位可以拿到更准的匹配分"——
         *    用价值引导，而不是用必填强迫。
         *
         * 【★ 它影响什么？】
         *    - 简历列表的展示（一眼看出投什么岗）
         *    - 将来"按岗位推荐简历"的筛选
         *    - 不直接影响分析 —— 分析用的是具体 JD
         */
        @Size(max = 64, message = "目标岗位最长 64 个字符")
        String targetPosition,

        /**
         * 简历正文（纯文本）。
         *
         * 【★★ 为什么这里【没有】@Size(max = ...) 限制长度？】
         *
         *   因为简历正文可能是几千到上万字。
         *   如果设一个上限（比如 10000），用户贴了 10001 字就被拒 ——
         *   但他根本不知道自己的简历"太长了"，会很困惑。
         *
         *   而且数据库是 MEDIUMTEXT（16MB），正常情况下用户不可能超。
         *
         *  ★ 真正需要限制的是【请求体大小】（防止恶意上传 100MB 文本），
         *    那应该在 HTTP 层限制（server.max-http-request-size），
         *    而不是在字段上限制。"不同层面的问题用不同层面的手段"。
         *
         * 【★ 只校验 @NotBlank，不校验最小长度，为什么？】
         *
         *  因为"至少 50 字"的校验需要先【归一化】——
         *  要去掉空白再算字符数。
         *  而 @Size 是直接数字符串长度（含空白）。
         *
         *  一份只有 10 个字、但带 40 个空格缩进的简历，
         *  @Size(min = 50) 会通过，但实际内容远不够。
         *
         *  ★ 所以：
         *     @NotBlank  → 挡"完全没填"
         *     Service 里 → 归一化后检查 charCount ≥ 50，抛 RESUME_CONTENT_INVALID
         *
         *  这是"注解管格式，Service 管业务规则"的分工。
         */
        @NotBlank(message = "请粘贴简历内容")
        String content
) {}
