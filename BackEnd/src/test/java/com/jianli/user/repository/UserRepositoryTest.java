package com.jianli.user.repository;

import com.jianli.user.entity.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  用户仓库的单元测试。验证"能往 app_user 插数据、能查回来"。
 *
 *  ★ 它还有一个隐藏作用：验证【实体和表结构完全对得上】。
 *    因为 @SpringBootTest 会启动整个应用，如果 AppUser 的某个 @Column
 *    写错了列名，启动时 ddl-auto=validate 就会失败，这个测试连跑都跑不起来。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：测试怎么走完整条链路】
 *
 *      运行本测试
 *         ↓
 *      Spring 启动应用上下文（和 start-backend.bat 启动时一样）
 *         ├─ 连接 MySQL
 *         ├─ Flyway 检查表结构
 *         ├─ JPA validate 校验 AppUser ↔ app_user 是否一致   ← ★ 对不上就在这里失败
 *         └─ 创建 UserRepository 的代理对象，注入到下面的字段
 *         ↓
 *      执行测试方法
 *         ├─ 构造一个 AppUser 对象（内存里）
 *         └─ userRepository.save(u) → 真实发 INSERT 到 MySQL
 *         ↓
 *      测试结束，@Transactional 让它【回滚】
 *         ↓
 *      数据库里什么都没留下
 *
 *  ★ 最后这条很重要：测试跑在真实数据库上，但不会污染数据。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *
 *  【类上两个注解，各起什么作用】
 *
 *  @SpringBootTest
 *      启动完整的 Spring 容器（包括数据库连接、Flyway、JPA 等）。
 *      因为没有它，@Autowired 注入不了任何东西。
 *
 *      ★ 代价：启动一次约 2-5 秒，比普通单元测试慢很多。
 *        所以"纯计算"的测试（比如后面的 LineDiffTest、PrivacyDetectorTest）
 *        【不要】加这个注解 —— 那些类不依赖 Spring，直接 new 就行，跑一次几十毫秒。
 *
 *  @Transactional（在测试类上）
 *      ★ 这是测试里的"自动回滚"开关。
 *      它的效果是：每个测试方法执行完，事务自动 ROLLBACK。
 *
 *      为什么需要？
 *        测试要往真实数据库插数据，如果不回滚，跑 100 次测试就留下 100 条垃圾数据，
 *        而且可能因为唯一索引冲突导致第二次跑就失败。
 *
 *      ★ 注意区分：
 *        在【生产代码】里，@Transactional 表示"这一组操作要么全成功要么全失败"（提交）
 *        在【测试类】上，@Transactional 表示"测完就撤销，别留痕迹"（回滚）
 *        同一个注解，语义完全不同 —— 这也是很多人第一次看测试代码会困惑的地方。
 */
@SpringBootTest
@Transactional
class UserRepositoryTest {

    /**
     * 由 Spring 自动注入。
     *
     * ★ 为什么字段上没有 new UserRepository(...)？
     *   因为 UserRepository 是接口，没有实现类可以 new。
     *   Spring 在启动时用动态代理生成了实现，并把那个代理对象塞进这个字段。
     *
     * ★ 如果注入失败会怎样？
     *   字段是 null，调用方法时抛 NullPointerException ——
     *   这通常意味着 UserRepository 没被扫描到（包名不对）
     *   或者 Spring 容器没起来（@SpringBootTest 少了）。
     */
    @Autowired
    private UserRepository userRepository;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【测试一】保存之后能查到
     * ═══════════════════════════════════════════════════════════════
     *
     *  【★ 为什么方法名用中文？】
     *  Java 标识符允许 Unicode，所以方法名可以用中文。
     *  好处是测试报告一眼能看出测的是什么：
     *      保存后能按用户名查到   ← 比 testSaveAndFind 清楚得多
     *  不喜欢的话改成英文也完全可以（比如 saveThenFindByUsername）。
     *
     *  ★ @Test 标记这是一个测试方法，JUnit 会自动执行它，
     *    并且每个 @Test 方法都会开一个【独立的事务】，互不影响。
     */
    @Test
    void 保存后能按用户名查到() {

        /*
         * 构造一个用户对象。
         *
         * ★ 为什么用户名要拼一个时间戳？
         *   因为 username 有唯一索引。如果写死 "test"，第一次跑成功，
         *   第二次跑就会因为唯一索引冲突失败（虽然 @Transactional 会回滚，
         *   但万一某次没回滚，就会留下脏数据）。
         *   加时间戳保证每次跑都不一样，测试可以反复执行。
         */
        AppUser u = new AppUser();
        u.setUsername("test_" + System.currentTimeMillis());
        u.setPasswordHash("$2a$10$fakehashforunitestonly");
        u.setNickname("测试用户");
        u.setTargetPosition("Java 后端开发");
        u.setYearsOfExperience(3);

        /*
         * 保存 → 真实执行 INSERT INTO app_user (...) VALUES (...)
         *
         * ★ 注意：我们【没有设置】createdAt / updatedAt，
         *   但保存后它们会有值 —— 这就是 AppUser 里 @PrePersist 的功劳。
         *
         * ★ 返回值 saved 和 u 是【同一个对象】（JPA 会把生成的 id 回填到 u），
         *   用返回值只是为了写法更清晰。
         */
        AppUser saved = userRepository.save(u);

        /*
         * 断言（assert）：验证"我以为的结果"和"实际结果"一致。
         * 用 AssertJ 的 assertThat，它比 JUnit 自带的 assert 可读性好很多，
         * 而且失败时会打印出期望值和实际值，方便排查。
         *
         * ★ 第一个断言验证什么？
         *   id 不为 null 说明【INSERT 成功并且自增 id 回填了】。
         *   如果 id 是 null，通常是 @GeneratedValue 配置错了。
         */
        assertThat(saved.getId()).isNotNull();

        /*
         * ★ 验证 @PrePersist 生效了。
         * 如果这个断言失败，说明生命周期回调没被触发（注解写错了方法上）。
         */
        assertThat(saved.getCreatedAt()).isNotNull();

        /*
         * 再查一次确认它真的写进数据库了（而不只是存在内存里）。
         *
         * ★ isPresent() → Optional 里有值
         *   get()       → 取出里面的对象
         *   如果查不到，isPresent 断言会直接失败并打印"期望有值但为空"。
         */
        var found = userRepository.findByUsername(saved.getUsername());
        assertThat(found).isPresent();
        assertThat(found.get().getNickname()).isEqualTo("测试用户");
        assertThat(found.get().getYearsOfExperience()).isEqualTo(3);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【测试二】用户名判重
     * ═══════════════════════════════════════════════════════════════
     *
     *  验证 existsByUsername 的两个方向：
     *      存在的用户名   → true
     *      不存在的用户名 → false
     *
     *  ★ 为什么两个方向都要测？
     *    只测 true 的话，如果方法实现有问题（比如永远返回 true），测试也会通过。
     *    必须同时验证"该返回 true 时返回 true，该返回 false 时返回 false"，
     *    才能确认逻辑是对的。这叫"正例 + 反例"。
     */
    @Test
    void 用户名判重() {
        String name = "dup_" + System.currentTimeMillis();

        AppUser u = new AppUser();
        u.setUsername(name);
        u.setPasswordHash("x");
        u.setNickname("n");
        u.setYearsOfExperience(0);      // ★ 这里必须设，虽然 @PrePersist 会兜底，
                                        //   但显式设置能看出这个字段是必填的
        userRepository.save(u);

        // 正例：刚存的用户名，应该存在
        assertThat(userRepository.existsByUsername(name)).isTrue();

        // 反例：不存在的用户名，应该返回 false
        assertThat(userRepository.existsByUsername("绝不存在的名字")).isFalse();
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 建议你额外做一次"故意制造错误"的验证】
     *
     *  把 AppUser 里这行：
     *      @Column(name = "target_position", length = 64)
     *  改成：
     *      @Column(name = "target_pos", length = 64)
     *
     *  然后重新运行这个测试，你会看到：
     *
     *      Schema-validation: missing column [target_pos] in table [app_user]
     *
     *  测试【根本跑不到方法体】，在 Spring 启动阶段就失败了。
     *
     *  ★ 这就是 ddl-auto=validate 的价值：
     *    列名写错、类型不对、表不存在 —— 全部在【启动时】暴露，
     *    而不是等用户点了某个接口才报 Unknown column。
     *
     *  看完记得改回来。
     * ═══════════════════════════════════════════════════════════════
     */
}
