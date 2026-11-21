package com.jianli.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  AI 相关的配置容器。对应 application.yml 里的 app.ai.*
 *
 *      app:
 *        ai:
 *          enabled: false            ← 总开关
 *          features:                 ← 分功能开关
 *            jd-parse: true
 *            interview: true
 *            privacy-assist: false
 *          timeout:                  ← 每个功能的超时
 *            jd-parse: 20s
 *          max-input-chars:
 *            jd: 6000
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      application.yml
 *         ↓  启动时绑定
 *      AiProperties（本类）
 *         ├─ MetaController      → /api/meta/features 告诉前端"AI 开没开"
 *         ├─ AiFeatureGate       → 判断"这个功能能不能用 AI"（步骤 10）
 *         └─ AiCallExecutor      → 取超时时间（步骤 10）
 *         ↓
 *      最终影响：用户能不能看到"AI 优化""面试预演"这些入口
 *
 *  【★ 为什么开关要分"总开关 + 分功能开关"两级？】
 *
 *  总开关（enabled）适合"整个环境都不开 AI"：
 *      比如 CI 环境、演示环境、没配 Key 的机器。
 *
 *  分功能开关适合"AI 开着，但某个功能先不上线"：
 *      比如 interview 还在调优，就先设成 false，用户看不到入口。
 *
 *  ★ 两级结合：必须"总开关开 且 分开关开"才真正可用。
 *
 *  【★ 为什么 maxInputChars 要限制输入长度？】
 *  模型按 token 计费，输入越长越贵。而且超长文本容易触发模型侧的长度上限。
 *  所以超过限制就截断（截断策略在 AiGateway 里）。
 *  6000 字 JD、8000 字简历，大约对应 3k-4k token，够用且成本可控。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(

        /**
         * 总开关。
         *
         * ★★ 默认必须在 yml 里写成 ${AI_ENABLED:false} —— 默认关闭。
         *   理由有三个：
         *     1. 本地开发 / CI / 演示环境不配 Key 也能跑
         *     2. 避免测试时反复消耗额度
         *     3. 面试演示时可以先展示"纯规则跑通全链路"，再开 AI 展示增强
         */
        boolean enabled,

        /** 分功能开关 */
        Features features,

        /**
         * 各功能的超时时间。
         *
         * ★ 为什么用 Map<String, Duration> 而不是每个功能一个字段？
         *   功能太多（6 个），每个都写一个 Duration 字段会让类很长。
         *   用 Map 更紧凑，而且增加新功能时不用改类结构。
         *
         * ★ 注意：Map 的 key 不做宽松绑定！
         *   yml 里写 jd-parse，Map 里就是 "jd-parse"（不会变成 "jdParse"）。
         *   所以 AiCallExecutor 里取的时候要用 "jd-parse" 这个字符串。
         */
        Map<String, Duration> timeout,

        /** 各功能允许的最大输入字符数 */
        Map<String, Integer> maxInputChars,

        /** 提示词版本。改了 prompt 就升级它，用于缓存失效 */
        String promptVersion
) {

    /**
     * 分功能开关。
     *
     * ★ 为什么用嵌套 record 而不是平铺在 AiProperties 里？
     *   因为 yml 里有嵌套结构（features 下面才是具体的开关）。
     *   嵌套 record 让 Java 结构和 yml 结构一一对应，看代码就知道配置长什么样。
     */
    public record Features(
            boolean jdParse,       // JD 结构化解析
            boolean resumeParse,   // 简历结构化解析
            boolean suggestions,   // 板块改进建议
            boolean rewrite,       // AI 简历重写（逐条）
            boolean interview,     // 面试预演题
            boolean privacyAssist  // 个人信息审核的 AI 辅助（默认关闭）
    ) {

        /**
         * ★ 便捷方法：判断某个功能是否开启。
         *
         * 为什么需要它？
         *   调用方如果写 apis.features().interview()，只能判断分开关；
         *   但真正"能不能用"需要【总开关 且 分开关】。
         *   封装成方法后，调用方不容易漏判总开关。
         *
         * ★ 注意这里没有用 switch 表达式，因为要传的 kind 只有一个 bool 结果。
         *   直接传 boolean 进来更简单。
         */
        public boolean anyEnabled(boolean masterEnabled) {
            return masterEnabled
                    && (jdParse || resumeParse || suggestions || rewrite || interview || privacyAssist);
        }
    }
}
