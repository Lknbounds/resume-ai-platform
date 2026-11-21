package com.jianli.ai.prompt;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  Prompt 模板的加载器。★ 把 Prompt 从代码里挪到 .md 文件。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么 Prompt 要独立成文件，不写在 Java 里？】
 *
 *  ① 可读性
 *     文本块（"""）虽然能写，但一个 2000 字的 Prompt 嵌在 Java 里
 *     会和缩进、转义符号混在一起，改起来很痛苦。
 *
 *  ② 可版本管理
 *     Prompt 改动在 git diff 里一目了然，
 *     而不是"某行 Java 字符串变了 300 个字符"。
 *
 *  ③ 便于调优
 *     改 Prompt 只改 .md，不用重新想"这个转义符号对不对"。
 *     项目里还记录了 prompt-version，配合缓存能验证"改了 Prompt 效果变了吗"。
 *
 *  ★ 这是"把配置和代码分开"原则在 Prompt 上的应用。
 */
@Slf4j
@Component
public class PromptTemplates {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 启动时预加载。
     *
     * ★ 为什么预加载而不是懒加载？
     *   懒加载的问题：第一次调用要读磁盘 + 首次调 AI 本来就慢，
     *   叠加起来可能触发超时。
     *   启动时加载几百毫秒，换来运行时零开销。
     *
     * ★ 加载失败怎么办？
     *   不抛异常 —— AI 是可选能力，Prompt 缺失只影响 AI 功能。
     *   返回空字符串，AiGateway 检测到空 Prompt 会直接降级。
     */
    @PostConstruct
    void preload() {
        for (String name : new String[]{"interview"}) {
            get(name);
        }
    }

    /**
     * 取 Prompt 模板。
     *
     * @param name 模板名（不含路径和扩展名），如 "interview"
     * @return 模板内容；不存在时返回空字符串
     */
    public String get(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    private String load(String name) {
        String path = "prompts/" + name + ".system.md";

        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String text = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            log.info("Prompt 模板加载完成：{}（{} 字符）", name, text.length());
            return text;
        } catch (Exception e) {
            log.warn("Prompt 模板缺失：{}，相关功能将降级", path);
            return "";
        }
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★★ 为什么不用 Thymeleaf / FreeMarker 做模板引擎？】
     *
     *  本项目 Prompt 的变量替换只有 `{xxx}` 这种简单占位符，
     *  Spring AI 的 ChatClient 本身就支持 `.param("key", value)`。
     *
     *  ★ 引一个模板引擎要几百 KB 依赖，还要学它的语法，
     *    收益只是"稍微优雅一点的字符串替换" —— 不值得。
     *
     *  ★ 如果将来 Prompt 复杂到需要条件分支、循环（比如"每条技能生成一段"），
     *    再考虑引入。现在不需要。
     * ═══════════════════════════════════════════════════════════════
     */
}
