package com.jianli.resume.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  简历详情。包含基本信息 + 版本列表（摘要，不含正文）。
 *
 *  返回的 JSON：
 *      {
 *        "id": 10, "title": "Java 后端简历",
 *        "currentVersionId": 35, "versionCount": 3,
 *        "versions": [
 *          {"id":35,"versionNo":3,"source":"ADOPT_COMMENT","charCount":1020,...},
 *          {"id":33,"versionNo":2,"source":"EDIT","charCount":940,...},
 *          {"id":31,"versionNo":1,"source":"PASTE","charCount":860,...}
 *        ],
 *        "createdAt": "...", "updatedAt": "..."
 *      }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么详情页的版本列表也不含正文？】
 *
 *  用户打开简历详情页时，看到的是：
 *      左侧：版本时间线（v1 v2 v3，每个显示字数、时间）
 *      右侧：某一个版本的【全文】
 *
 *  ★ 全文只需要"当前选中那一个版本"的。
 *    一次把所有版本的正文都传过来，是 5 倍的数据量。
 *
 *  所以流程是：
 *      1. 本接口 → 拿到版本列表（摘要）
 *      2. 前端默认选中 currentVersionId
 *      3. 再调 GET /resumes/versions/{versionId} → 拿那一份的全文
 *
 *  ★ 这是"按需加载"，也是"列表接口轻量"的具体应用。
 *
 *
 *  【★ 数据流向：这个 VO 在哪被组装】
 *
 *      ResumeService.create()   → detailOf(resume)   新建后直接返回
 *      ResumeService.detail()   → detailOf(resume)   打开详情页时
 *      ResumeService.rename()   → detailOf(resume)   改标题后
 *              ↓
 *      detailOf 里做两件事：
 *         1. 用 resume 的字段填基本信息
 *         2. 查版本列表 → 每个 map 成 VersionSummaryVO
 *              ↓
 *      Controller 包成 ApiResponse<ResumeDetailVO>
 */
public record ResumeDetailVO(

        Long id,

        String title,

        /**
         * 目标岗位。可空。
         *
         * ★ 详情页顶部会显示"目标岗位：Java 后端开发工程师"，
         *   旁边有个编辑按钮，点了能改。
         *
         * ★ 这个信息在详情页的作用：
         *   让用户确认"我打开的是不是那份投 Java 的简历"。
         */
        String targetPosition,

        /**
         * 当前版本 id。
         *
         * ★ 前端用它做两件事：
         *   1. 详情页默认选中这个版本（打开就显示最新内容）
         *   2. 时间线上给这个版本加个"当前"标记
         */
        Long currentVersionId,

        /** 版本数。其实等于 versions.size()，但单独给一个字段语义更明确 */
        Integer versionCount,

        /**
         * 版本列表（摘要），按版本号倒序 —— 最新的在最上面。
         *
         * ★ 为什么不正序（v1 在最上面）？
         *   用户最常看的是最新版本。
         *   v1 在最上面的话，版本多了要往下翻才能看到最新的。
         *   ★ 时间线组件也是从新到旧更符合直觉（像 git log）。
         */
        List<VersionSummaryVO> versions,

        LocalDateTime createdAt,

        LocalDateTime updatedAt

        /*
         * ═══════════════════════════════════════════════════════════
         *  【★ 注意：没有 content 字段】
         *
         *  详情 VO 也没有正文 —— 因为"哪一版的正文"是不确定的。
         *  用户可能选中 v1，也可能选中 v3。
         *
         *  ★ 一个 VO 对应"确定的数据"。
         *    正文是"随选中版本变化"的，所以必须单独接口取。
         *
         *  如果硬要在这里塞 content，会变成：
         *      "content": "<当前版本的正文>"   ← 那选 v1 怎么办？
         *      "v1Content": "..."               ← 那 v2、v3 呢？
         *      "v2Content": "..."               ← 越加越多，最后就是全传
         *
         *  ★ 这是"接口职责要单一"的体现：
         *    详情接口负责"有哪些版本"，
         *    版本接口负责"某个版本的内容"。
         * ═══════════════════════════════════════════════════════════
         */
) {}
