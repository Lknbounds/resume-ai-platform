package com.jianli.user.controller;

import com.jianli.common.ApiResponse;
import com.jianli.config.AiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  告诉前端"这台服务器现在开了哪些功能"。目前只有一个接口。
 *
 *      GET /api/meta/features
 *
 *  响应：
 *      {
 *        "aiEnabled": false,
 *        "aiFeatures": {"jdParse":false,"resumeParse":false,...},
 *        "interviewModuleEnabled": true,
 *        "externalInfoEnabled": true,
 *        "scoringVersion": "v1"
 *      }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么前端需要这个接口？】
 *
 *  ★★ 当前面试模块独立于 AI：AI 关闭时仍使用本地 5 题题库。
 *
 *  场景：如果 AI_ENABLED=false（面试预演题没有规则替代品），
 *        前端必须把"面试预演"入口【完全隐藏】，不能让用户点了才发现报错。
 *
 *  前端做法（见 04 文档的 router 和 DefaultLayout）：
 *      1. 应用启动时调一次本接口，结果存进 useFeatureStore
 *      2. 导航栏：v-if="feature.interviewModuleEnabled" 决定显不显示
 *      3. 路由守卫：直接输 URL 也进不去
 *
 *  ★ 三重保障：导航隐藏 + 路由拦截 + 接口返回 44001。
 *    前两个是体验，第三个才是真正的边界。
 *
 *
 *  【★ 为什么是公开接口（不需要登录）？】
 *
 *  因为它返回的是"这个部署实例的能力"，不是用户私有数据。
 *  而且前端在【登录页】就可能需要它（比如登录页上决定要不要显示某些入口）。
 *  所以 SecurityConfig 里放行了 /api/meta/**。
 *
 *  ★ 安全性考虑：暴露"AI 开没开"有没有风险？
 *    没有。这不是敏感信息，攻击者探测这个接口也得不到可利用的东西。
 *    而且真正调用 AI 时服务端还会再判一次开关（AiFeatureGate），
 *    前端即使被绕过也调不通。
 * ═══════════════════════════════════════════════════════════════════════
 */
@RestController
@RequestMapping("/api/meta")
@Tag(name = "元信息")
@RequiredArgsConstructor
public class MetaController {

    /**
     * 注入 AI 配置（只读，本类不改它）。
     */
    private final AiProperties aiProperties;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【查功能开关】GET /api/meta/features
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 每个字段为什么这么算：
     *
     *   aiEnabled = 总开关
     *       "这台机器有没有接 AI"
     *
     *   aiFeatures.xxx = 总开关 AND 分开关
     *       ★ 关键：不能只返回分开关。
     *         如果总分开关是 false，但分开关是 true，
     *         前端会以为"AI 优化可用"，用户点了却被 30001 拒绝。
     *         所以这里必须做 AND 运算，前端拿到的就是"真实可用性"。
     *
     *   interviewModuleEnabled = true（当前本地题库始终可用）
     *       ★ 单独抽一个字段是因为它是"整个模块"的开关，
     *         前端用它控制导航栏入口 + 路由守卫。
     *         虽然和 aiFeatures.interview 重复，但语义更明确：
     *         "这个模块整体可见吗" vs "这个 AI 能力可用吗"。
     *
     *   externalInfoEnabled = 写死 true
     *       "参考公开信息"模式（本地面经数据集）。
     *       它不依赖 AI（数据集是静态文件），所以恒为 true。
     *       ★ 步骤 15 会接上真实配置 app.interview.external-info.enabled。
     *
     *   scoringVersion = "v1"
     *       评分算法版本。前端可以显示"当前算法版本 v1"，
     *       也让"不同版本的分数不可比"这件事对用户可见。
     *       ★ 步骤 9 会接上配置里的 app.scoring.version。
     */
    @GetMapping("/features")
    @Operation(summary = "功能开关", description = "前端据此隐藏不可用的模块入口")
    public ApiResponse<FeatureVO> features() {

        var ai = aiProperties.features();
        boolean master = aiProperties.enabled();

        return ApiResponse.ok(new FeatureVO(
                master,

                /*
                 * ★ 这里用一行写 6 个 AND，看着有点密。
                 *   但比起在 Service 里拼装，直接在这里算更直观：
                 *   看这个表达式就知道"某个能力 = 总开关 && 分开关"。
                 *
                 * ★ 注意 AiFeatures 前面必须带 FeatureVO. ——
                 *   因为它是嵌套在 FeatureVO 里的 record，
                 *   不写外层类名的话编译器找不到（这就是刚才编译报错的原因）。
                 */
                new FeatureVO.AiFeatures(
                        master && ai.jdParse(),
                        master && ai.resumeParse(),
                        master && ai.suggestions(),
                        master && ai.rewrite(),
                        master && ai.interview(),
                        master && ai.privacyAssist()
                ),

                true,                       // 面试模块独立于 AI，始终可见
                true,                       // 参考公开信息模式（步骤 15 接配置）
                "v1"                        // 评分算法版本（步骤 9 接配置）
        ));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【响应结构】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么用嵌套 record 而不是一堆平铺字段？
     *   因为 aiFeatures 是一组相关的能力开关，嵌套后前端能写：
     *       if (feature.aiFeatures.rewrite) { 显示 AI 优化按钮 }
     *   比 feature.aiJdParse、feature.aiResumeParse 一长串更好读。
     *
     * ★ 为什么字段名是驼峰（aiEnabled）而 yml 里是短横线（privacy-assist）？
     *   yml 是配置文件（习惯用短横线），JSON 是接口（习惯用驼峰）。
     *   ★ 切换发生在 Map 绑定和 record 构造时 —— 我们手动做的。
     *     如果直接用 Map 返回，字段名就会是短横线，前端不好用。
     *     所以这里显式构造 record，把命名转换放在一处。
     */
    public record FeatureVO(
            boolean aiEnabled,
            AiFeatures aiFeatures,
            boolean interviewModuleEnabled,
            boolean externalInfoEnabled,
            String scoringVersion
    ) {
        public record AiFeatures(
                boolean jdParse,
                boolean resumeParse,
                boolean suggestions,
                boolean rewrite,
                boolean interview,
                boolean privacyAssist
        ) {}
    }
}
