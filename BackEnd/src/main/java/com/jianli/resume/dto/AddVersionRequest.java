package com.jianli.resume.dto;

import com.jianli.resume.enums.VersionSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  新增简历版本的请求体。
 *
 *      POST /api/resumes/{id}/versions
 *      {
 *        "content": "（编辑后的完整简历正文）",
 *        "changeNote": "补充项目量化数据",
 *        "source": "EDIT"
 *      }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      用户在详情页改完内容点"保存为新版本"
 *         ↓
 *      ResumeService.addVersion()
 *         ├─ 锁住简历行（FOR UPDATE）
 *         ├─ 归一化 + 校验字数
 *         ├─ 算 hash，检查是否和已有版本重复
 *         ├─ 算下一个版本号
 *         ├─ INSERT 新版本（parent = 当前版本）
 *         └─ 更新 resume 的 current_version_id / version_count
 *         ↓
 *      返回新版本的详情（含 versionNo）
 */
public record AddVersionRequest(

        /**
         * 新的简历正文（完整内容，不是 diff）。
         *
         * 【★★ 为什么传完整正文而不是"改动部分"？】
         *
         *  传 diff（只传改了哪几句）看起来省流量，但有两个严重问题：
         *
         *   1. 【前端要算 diff】—— 而 diff 算法后端已经有了（步骤 6），
         *      前端再实现一套容易出现"前后端算出的 diff 不一致"。
         *
         *   2. 【应用 diff 可能失败】—— 如果用户开了两个标签页，
         *      一个改了 v2、另一个还基于 v1 提交 diff，
         *      应用之后会得到一份谁都没预期的内容。
         *
         *  传完整正文 + 服务端整体替换，是最简单也最安全的方式：
         *    - 每次提交的都是"用户看到的、确认过的完整内容"
         *    - 不存在"应用失败"的可能
         *    - 版本是快照，本来就应该存完整内容
         *
         *  ★ 代价是流量大一点（一份简历几十 KB），完全可以接受。
         *
         *  ★ 这也是"版本对比"能工作的前提：
         *    两个版本各存完整内容，diff 才能算得出来。
         */
        @NotBlank(message = "简历内容不能为空")
        String content,

        /**
         * 变更说明。选填。
         *
         * ★ 作用：版本时间线上显示"这版改了什么"。
         *   不填也没关系，但显示时会退化成显示 source（如"EDIT"）。
         *
         * ★ @Size(max = 255) 对应数据库 VARCHAR(255)。
         */
        @Size(max = 255, message = "变更说明最长 255 个字符")
        String changeNote,

        /**
         * 版本来源。★ 可以为 null。
         *
         * 【★ 为什么允许 null，而不是给个默认值？】
         *
         *   如果前端不传，Service 里会默认成 EDIT：
         *       req.source() == null ? VersionSource.EDIT : req.source()
         *
         *   ★ 那为什么不在这里直接写默认值？
         *     record 没法设默认值（不像类可以有字段初始化）。
         *     而用 @NotNull + 前端必传 又太严格 ——
         *     大多数情况就是"用户手动改的"，没必要逼前端传。
         *
         * 【★ 为什么不让前端传 ADOPT_COMMENT？】
         *
         *   这是个安全隐患：如果前端能随便指定 source，
         *   用户就能伪造"我是采纳了社区建议才改的"，
         *   污染"采纳效果"的统计数据。
         *
         *   ★ 所以规则是：
         *     普通接口只接受 PASTE / EDIT（Service 里可以再过滤一次）
         *     ADOPT_COMMENT 只能由 AdoptionService（步骤 14）内部设置
         *
         *   本项目简化处理：Service 里不额外校验，但注释说明这个约定。
         *   如果要严谨，可以在 Service 里这样写：
         *       if (source == ADOPT_COMMENT) throw FORBIDDEN;
         */
        VersionSource source,

        /** 新版本基于哪个旧版本。为空时使用当前版本。 */
        Long parentVersionId
) {}
