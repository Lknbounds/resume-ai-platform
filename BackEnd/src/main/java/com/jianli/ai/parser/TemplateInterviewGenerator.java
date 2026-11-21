package com.jianli.ai.parser;

import com.jianli.ai.model.InterviewQ;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 本地面试题生成器。当前项目不调用外部 AI，所有题目都由这里生成。
 *
 * 生成结果始终围绕五类技术能力展开：Java、Spring Boot、MySQL、Redis、并发与性能。
 * 每道题都带参考答案和辅助信息，参考答案给的是可迁移的回答框架，不替用户编造经历。
 */
@Component
public class TemplateInterviewGenerator {

    public List<InterviewQ> generate(String position,
                                     List<String> jdSkills,
                                     List<String> projects,
                                     List<String> experiences,
                                     int count) {
        List<String> safeProjects = clean(projects);
        List<String> safeExperiences = clean(experiences);
        List<String> safeSkills = clean(jdSkills);
        List<InterviewQ> out = new ArrayList<>();

        for (int i = 0; i < Math.min(2, safeProjects.size()) && out.size() < count; i++) {
            out.add(projectQuestion(safeProjects.get(i), i));
        }

        for (int i = 0; i < Math.min(2, safeExperiences.size()) && out.size() < count; i++) {
            out.add(experienceQuestion(safeExperiences.get(i), i));
        }

        int skillIndex = 0;
        while (out.size() < count) {
            if (skillIndex < safeSkills.size()) {
                out.add(skillQuestion(safeSkills.get(skillIndex++)));
            } else {
                out.add(generalQuestion(out.size(), position));
            }
        }

        return List.copyOf(out);
    }

    private InterviewQ projectQuestion(String project, int index) {
        String name = projectName(project);


        if (index == 0) {
            return new InterviewQ(
                    "PROJECT",
                    "你在「%s」这个项目里负责哪部分？整体架构是怎么设计的？".formatted(name),
                    """
                    可以按“职责边界、架构分层、技术选型、规模指标、复盘”五步回答。
                    先明确自己负责的模块，再说明上下游和数据流；技术选型要讲约束与备选方案。
                    最后补充用户量、数据量或性能指标，并诚实说明如果重做会优化哪里。
                    """.stripIndent().trim(),
                    "验证真实参与度、架构理解和技术决策能力",
                    List.of(
                            "具体职责边界：做了什么、没有做什么",
                            "整体架构、关键模块和核心数据流",
                            "技术选型的理由与被放弃的备选方案",
                            "可量化的项目规模或性能结果"
                    ),
                    "如果让你重新设计，你会改哪一部分？为什么？",
                    project
            );
        }

        return new InterviewQ(
                "PROJECT",
                "「%s」在开发或上线时遇到过什么技术问题？你是怎么定位和解决的？".formatted(name),
                """
                使用 STAR-L 结构：先描述现象和影响，再说明监控、日志、压测或二分排查过程。
                根因要区分直接原因和深层原因，解决方案要包含修复、验证和防复发措施。
                如果没有真实故障，可以讲开发阶段的性能、数据一致性或并发问题，但不要编造指标。
                """.stripIndent().trim(),
                "考察问题定位、根因分析和工程闭环",
                List.of(
                        "问题现象、影响范围和关键报错或指标",
                        "定位过程以及每一步排除了什么",
                        "根因，而不是只停在表面现象",
                        "修复方案、效果验证与防复发手段"
                ),
                "如果这个问题只在生产环境复现，你会怎么继续排查？",
                project
        );
    }

    private InterviewQ experienceQuestion(String experience, int index) {
        String text = shorten(experience, 34);
        if (index == 0) {
            return new InterviewQ(
                    "EXPERIENCE",
                    "在「%s」这段经历中，哪些工作最能体现你的技术能力？".formatted(text),
                    """
                    选择一个与目标岗位最相关的真实任务，用“背景—任务—行动—结果—复盘”展开。
                    行动部分突出个人贡献和技术判断，结果尽量用真实数据或可验证的交付物支撑。
                    没有量化数据时，可以说明稳定性、可维护性、上线范围或协作产出的变化。
                    """.stripIndent().trim(),
                    "考察技术贡献的真实性、表达条理和结果意识",
                    List.of(
                            "任务背景、目标和个人职责",
                            "采用的技术方案以及关键取舍",
                            "最难的问题和解决过程",
                            "结果、验证方式与后续改进"
                    ),
                    "这段经历里有没有做得不够好、后来反思改进的部分？",
                    experience
            );
        }

        return new InterviewQ(
                "EXPERIENCE",
                "这段经历里你做过哪些技术决策？当时有哪些备选方案？",
                """
                先交代时间、成本、团队能力和存量系统等约束，再列两个以上备选方案。
                对每个方案比较性能、复杂度、维护成本、风险和迁移成本，最后解释为什么选当前方案。
                结尾说明决策结果如何验证，以及约束变化后是否会重新选择。
                """.stripIndent().trim(),
                "考察技术选型的思考过程，而不是只会使用工具",
                List.of(
                        "决策前的具体约束和目标",
                        "至少两个备选方案及各自优缺点",
                        "最终选择的关键依据",
                        "上线后的验证结果和风险预案"
                ),
                "如果这个方案后来被证明有问题，回滚成本高吗？",
                experience
        );
    }

    private InterviewQ skillQuestion(String skill) {
        String label = skill == null || skill.isBlank() ? "这项技术" : skill.trim();
        return new InterviewQ(
                "TECHNICAL",
                "你在项目里是怎么使用 %s 的？遇到过什么问题？".formatted(label),
                answerForSkill(label),
                "验证 %s 是真正用过，还是只了解概念".formatted(label),
                List.of(
                        "具体使用场景和要解决的问题",
                        "关键配置、接口或工程实践",
                        "%s 的核心原理与常见坑".formatted(label),
                        "效果验证，以及与同类方案的对比"
                ),
                "%s 在什么场景下不适合使用？".formatted(label),
                null
        );
    }

    private InterviewQ generalQuestion(int index, String position) {
        List<InterviewQ> pool = List.of(
                new InterviewQ(
                        "TECHNICAL",
                        "针对%s岗位，最重要的技术能力是什么？你目前做到了什么程度？".formatted(
                                position == null || position.isBlank() ? "这个" : position),
                        """
                        先根据岗位职责归纳两到三项核心能力，再用真实项目或学习成果证明掌握程度。
                        每项能力都区分“知道概念、能独立使用、能做方案、能排查疑难问题”等层级。
                        对短板给出可执行的学习计划，但不要把未完成的计划包装成已有经验。
                        """.stripIndent().trim(),
                        "考察岗位理解、自我认知和学习计划",
                        List.of("岗位核心能力", "与之匹配的真实证据", "当前短板", "具体可执行的提升计划"),
                        "如果三个月内补一个短板，你会怎么安排？",
                        null),
                new InterviewQ(
                        "TECHNICAL",
                        "讲一个你最近解决的技术问题，从发现到解决完整说一下。",
                        """
                        选择一个细节足够多的真实问题，按“现象—影响—假设—排查—根因—修复—验证”回答。
                        排查过程不要跳步，要说明为什么做下一步，以及哪些假设被排除。
                        最后总结沉淀，例如增加监控、补充测试、完善文档或调整设计。
                        """.stripIndent().trim(),
                        "考察问题排查基本功和表达条理",
                        List.of("现象和影响", "排查路径", "根因", "解决方案和验证", "沉淀与预防"),
                        "如果同样现象再次出现，你能更快定位吗？靠什么？",
                        null),
                new InterviewQ(
                        "TECHNICAL",
                        "你平时怎么学习新技术？最近深入学习的一个技术是什么？",
                        """
                        用一个具体技术回答，说明学习目标、资料来源、实践方式和验证结果。
                        可以讲官方文档、源码、小实验、项目落地或问题复盘，重点是形成闭环。
                        最后说出仍然不清楚的边界，比声称“完全掌握”更可信。
                        """.stripIndent().trim(),
                        "考察学习方法、技术热情和真实深度",
                        List.of("选择该技术的原因", "学习渠道与实践方式", "能独立完成的事情", "仍未掌握的边界"),
                        "学习中遇到过理解不了的难点吗？是怎么突破的？",
                        null)
        );

        return pool.get(Math.min(Math.max(index, 0), pool.size() - 1));
    }

    private String answerForSkill(String skill) {
        String key = skill.toLowerCase(Locale.ROOT);

        if (key.contains("java")) {
            return """
                   可以从一个真实场景切入，说明 Java 在其中承担的职责，再讲语言和工程层面的关键选择。
                   例如集合与数据结构的选择、并发安全、异常边界、JVM 内存或性能排查。
                   回答时给出遇到的问题、分析依据和验证结果，并说明什么场景下 Java 不是最佳选择。
                   """;
        }
        if (key.contains("spring")) {
            return """
                   先说明 Spring Boot 在项目中的作用，例如依赖注入、自动配置、Web 分层、事务或可观测性。
                   再讲一个真实配置或故障场景，解释 Bean 生命周期、事务边界、代理机制或自动配置为什么这样工作。
                   最后说明测试、监控和灰度验证，避免只罗列注解名称。
                   """;
        }
        if (key.contains("mysql") || key.contains("sql") || key.contains("数据库")) {
            return """
                   先交代表结构、数据规模和查询场景，再说明索引设计、事务隔离、锁或 SQL 优化。
                   可用 EXPLAIN、慢查询日志和监控指标说明如何验证，而不是只说“加了索引所以变快”。
                   同时覆盖主键、分页、深分页、连接查询和数据一致性等边界。
                   """;
        }
        if (key.contains("redis") || key.contains("缓存")) {
            return """
                   说明缓存了什么数据、为什么需要缓存，以及过期、淘汰、序列化和容量规划。
                   结合一个真实问题讲缓存穿透、击穿、雪崩或与数据库的一致性策略。
                   最后给出命中率、响应时间等验证指标，以及缓存不可用时的降级方案。
                   """;
        }
        if (key.contains("并发") || key.contains("多线程") || key.contains("性能")) {
            return """
                   用真实任务说明并发目标和瓶颈，再解释线程池、锁、原子类、不可变性或异步化的选择。
                   重点讲可见性、有序性、竞态、死锁、线程池队列和背压等风险，以及如何压测验证。
                   最后说明在吞吐量、延迟和资源占用之间的取舍。
                   """;
        }

        return """
               可以按“场景—方案—原理—问题—验证”五步回答。
               先说明在什么业务场景使用 %s，再解释关键设计和底层原理。
               然后讲一个真实踩坑与解决过程，最后给出测试、监控或压测结果。
               """.formatted(skill);
    }

    private String projectName(String project) {
        int index = project.indexOf('（');
        if (index < 0) index = project.indexOf('(');
        return index > 0 ? project.substring(0, index).trim() : project;
    }


    private String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private List<String> clean(List<String> source) {
        if (source == null) return List.of();
        return source.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}