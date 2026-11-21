package com.jianli.user.repository;

import com.jianli.user.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  用户表的数据访问层。在传统三层架构里，它对应你的 Mapper / DAO。
 *
 *  ★ 但和 Mapper 有本质区别：这里【没有实现类，也没有一行 SQL】。
 *    你只需要声明"我想按什么条件查"，SQL 由 Spring 自动生成。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：它在链路里的哪一环】
 *
 *      UserService.addUser(...)
 *         │  userRepository.save(user)
 *         ↓
 *      【本接口】save() / findByUsername() ...     ← 没有实现类！
 *         │  Spring 启动时动态生成了一个实现类：
 *         │     save()          → INSERT INTO app_user (...) VALUES (...)
 *         │     findByUsername  → SELECT * FROM app_user WHERE username = ?
 *         ↓
 *      JDBC 发给 MySQL
 *         ↓
 *      MySQL 返回结果集（一行行的字符串/数字）
 *         ↓  JPA 按 AppUser 上的 @Column 注解映射
 *      返回 Optional<AppUser>（Entity 对象）
 *         ↓
 *      回到 Service —— 由 Service 决定怎么处理（转 VO / 抛异常 / 继续算）
 *
 *  ★ 记住：Repository 只负责"取/存数据"，【不做任何业务判断】。
 *    "用户不存在就抛 404" 这种逻辑属于 Service，不属于这里。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *
 *  【★ 一个没有实现类的接口，怎么可能干活？】
 *  这是 Spring Data JPA 最神奇也最需要理解的地方。
 *
 *  启动时 Spring 做了三件事：
 *    1. 扫描到本接口继承了 JpaRepository
 *    2. 解析方法名：findBy + Username
 *       → "查" + "按 username 字段"
 *    3. 用动态代理在内存里生成一个实现类，把方法名翻译成 SQL，
 *       然后把这个实现类注册成 Bean，注入到需要它的 Service 里
 *
 *  所以你调用 userRepository.findByUsername("alice") 时，
 *  实际执行的是 Spring 生成的那个代理对象，它内部发了一条 SQL。
 *
 *
 *  【JpaRepository<AppUser, Long> 的两个泛型是什么】
 *      第一个 AppUser → 这个仓库管哪个实体类（决定查哪张表）
 *      第二个 Long    → 这个实体的主键类型
 *
 *  继承之后，【白拿】这些方法（不用写一行代码）：
 *      save(entity)            → INSERT 或 UPDATE
 *      saveAll(list)           → 批量
 *      findById(id)            → SELECT ... WHERE id = ?
 *      findAll()               → 全表（生产慎用）
 *      delete(entity)          → DELETE
 *      deleteById(id)          → DELETE WHERE id = ?
 *      count()                 → SELECT COUNT(*)
 *      existsById(id)          → 判断存在
 *
 *  ★ 为什么这个接口是 interface 不是 class？
 *    Spring Data 只认接口。它靠动态代理在运行时生成实现，
 *    而 Java 的动态代理【只能代理接口】，不能代理类。
 *    （这一点面试可能会问，是理解"方法名怎么变成 SQL"的关键。）
 * ═══════════════════════════════════════════════════════════════════════
 */
public interface UserRepository extends JpaRepository<AppUser, Long> {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【方法名即查询】Spring 解析这个名字，自动生成：
     *      SELECT * FROM app_user WHERE username = ? LIMIT 1
     * ═══════════════════════════════════════════════════════════════
     *
     *  名字的拆解规则：
     *      find  By  Username
     *       ↑     ↑     ↑
     *      动作  分隔符  条件字段（对应 AppUser.username）
     *
     *  ★ 为什么返回 Optional<AppUser> 而不是 AppUser？
     *    因为查不到是【正常情况】不是异常。用 Optional 强迫调用方
     *    显式处理"可能没有"：
     *
     *        // 返回 AppUser 的话，查不到就是 null，很容易忘了判空
     *        AppUser u = repo.findByUsername("xxx");
     *        u.getNickname();              // ← 查不到时 NPE
     *
     *        // 返回 Optional 的话，编译器不逼你，但写法自然带出处理分支
     *        repo.findByUsername("xxx")
     *            .map(UserVO::of)
     *            .orElseThrow(() -> new BizException(USER_NOT_FOUND));
     *
     *    而且调用处可以选择用 orElseThrow / orElse / ifPresent，
     *    语义比 if (u != null) 更清晰。
     *
     *  ★ 用在哪儿：登录时按用户名查用户（步骤 3 的 AuthService.login）
     */
    Optional<AppUser> findByUsername(String username);

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【existsBy...】只判断存在，不取数据。生成：
     *      SELECT 1 FROM app_user WHERE username = ? LIMIT 1
     * ═══════════════════════════════════════════════════════════════
     *
     *  ★ 为什么不直接用 findByUsername(...).isPresent()？
     *    性能差距很大：
     *      existsByUsername → SELECT 1 ... LIMIT 1    只取一个常量
     *      findByUsername   → SELECT * ...            要把所有列都读出来
     *    注册判重只需要"有没有"，不需要"是什么"，用前者更合适。
     *
     *  ★ 用在哪儿：注册时判断用户名是否已存在（步骤 3 的 AuthService.register）
     *
     *  ★ 注意：这个方法返回 boolean，不是 Optional<Boolean>。
     *    因为"存不存在"这个问题本身一定有答案，不存在"没有答案"的情况。
     */
    boolean existsByUsername(String username);

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【后面会往这个接口里加什么】
     *
     *  目前（步骤 2）只需要这两个。后面用到再逐个加：
     *
     *      // 改密码、禁用账号时踢掉该用户所有 refresh token（步骤 3/附B）
     *      // 这个不需要 Repository，走 Redis
     *
     *      // 管理端用户列表（二期 RBAC）
     *      Page<AppUser> findByNicknameContaining(String kw, Pageable pageable);
     *
     *  规律：需要什么查询就加一个方法，方法名按下面的规则拼。
     *
     *  ┌───────────────── 方法名语法速查 ─────────────────┐
     *  │ findBy / readBy / queryBy / getBy                │  前缀，意思都一样
     *  │ ...And... / ...Or...                             │  多条件拼接
     *  │ ...OrderByXxxDesc / Asc                         │  排序
     *  │ ...First / ...Top3                              │  限制条数
     *  │ ...Count / ...Exists                            │  返回计数 / 布尔
     *  │ Containing / StartingWith / GreaterThan / Between│  模糊/前缀/区间
     *  └──────────────────────────────────────────────────┘
     *
     *  例：
     *      findByUserIdAndStatusOrderByCreatedAtDesc(userId, status)
     *      → SELECT * FROM ... WHERE user_id = ? AND status = ?
     *        ORDER BY created_at DESC
     *
     *  ★ 方法名拼错（比如写成 findByUserName）会在【启动时】报错，
     *    不会等到运行时。这是好事 —— 早发现早改。
     * ═══════════════════════════════════════════════════════════════
     */
}
