package com.jianli.ai;

import com.jianli.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  AI 功能的开关判断。★ 全项目所有"能不能用 AI"都问它。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么要把开关判断收口到一个类？】
 *
 *  如果每个 Service 都写：
 *      if (props.enabled() && props.features().interview()) { ... }
 *
 *  问题：
 *      1. 重复几十次
 *      2. 总有人只判了分开关、忘了判总开关
 *         → AI_ENABLED=false 时某个功能还能调 AI
 *      3. 前端要的开关快照也没法统一生成
 *
 *  ★ 收口之后：
 *      - 判断逻辑一处定义
 *      - 前端开关快照也从这里出，保证"前端看到的"和"后端执行的"一致
 */
@Component
@RequiredArgsConstructor
public class AiFeatureGate {

    private final AiProperties props;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【这个功能能用 AI 吗】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 必须【总开关 且 分开关】都为 true。
     *
     * ★ switch 表达式的好处：加新功能时编译器会提醒你补分支
     *   （如果用 if-else 链，漏一个不会报错，只会静默返回 false）。
     */
    public boolean isAvailable(AiFeature feature) {
        if (!props.enabled()) {
            return false;
        }

        var f = props.features();
        if (f == null) return false;

        return switch (feature) {
            case JD_PARSE       -> f.jdParse();
            case RESUME_PARSE   -> f.resumeParse();
            case SUGGESTIONS    -> f.suggestions();
            case REWRITE        -> f.rewrite();
            case INTERVIEW      -> f.interview();
            case PRIVACY_ASSIST -> f.privacyAssist();
        };
    }

    /** 总开关 */
    public boolean aiEnabled() {
        return props.enabled();
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 为什么不在这里检查 API Key？】
     *
     *  因为如果没有 Key，Spring AI 的自动配置根本不会创建 ChatClient Bean
     *  （见 application.yml 里的 spring.ai.dashscope.enabled: ${AI_ENABLED}）。
     *
     *  ★ 所以"有没有 Key"和"AI_ENABLED"是绑定的：
     *     AI_ENABLED=true 却没 Key → 启动时就会失败（Assert.hasText）
     *     AI_ENABLED=false        → 不初始化，也就没有调用问题
     *
     *  ★ 结论：开关本身就是最可靠的判断，不需要额外检查 Key。
     * ═══════════════════════════════════════════════════════════════
     */
}
