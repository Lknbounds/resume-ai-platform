# 附B：二期设计 · 管理员后台与 RBAC（主功能敲完再做）

> **当前状态：不做。** 你在 2026-11-18 明确说"等我全部敲完再弄"，同时也说"主要想学一下权限 RBAC 那个功能"。
> 所以这篇是**预习 + 实施清单**：等步骤 1-16 跑通后，照着做 1.5-2 天就能加完。
>
> 一期原则不变：普通用户只能管理**自己的**简历、JD、报告、帖子、评论（Service 层 `ownerUserId` 校验）。

---

## B.1 RBAC 解决什么问题

**没有 RBAC（单角色字段）**：

```sql
ALTER TABLE app_user ADD COLUMN role VARCHAR(16) DEFAULT 'USER';   -- USER / ADMIN
```

```java
@PreAuthorize("hasRole('ADMIN')")
```

能跑，但一旦出现"运营只能隐藏帖子、不能禁用用户""审核员能删评论但不能看统计"，就只能不停加角色（ADMIN、OPS、MODERATOR…），**角色爆炸且大量重复**。

**RBAC**：把"谁能做什么"拆成三层。

```
用户 User ──(多对多)── 角色 Role ──(多对多)── 权限 Permission
```

- **权限（Permission）** 是最小可操作单元：`POST:HIDE`、`USER:DISABLE`
- **角色（Role）** 是权限的集合：`ADMIN` = 全部，`MODERATOR` = 内容相关
- **用户** 可以多角色，权限取并集

加一个新岗位只需建角色 + 勾权限，**不改代码**。

---

## B.2 为什么权限不能塞进 JWT

JWT 签发后内容就固定了。如果把 `roles:["ADMIN"]` 写进去：

- **撤销**某人的管理员权限后，他手里的 token 在 2 小时内依然有效 —— 撤权失效；
- **新增**权限也要等 token 过期才生效。

正确做法：

| 内容 | 放哪 | 原因 |
|---|---|---|
| `userId`、`jti`、过期时间 | JWT | 身份标识，不需要变更 |
| 角色 / 权限清单 | 服务端（DB + Redis 缓存），每请求加载 | 可随时撤销、立即生效 |

本项目已有 Redis，天然可以缓存权限集合（`auth:perm:{userId}`，TTL 5 分钟，变更时主动删）。

---

## B.3 表结构（`V2__rbac.sql`）

```sql
-- 1. 用户启用状态（禁用 = 禁止登录，不删数据）
ALTER TABLE `app_user`
  ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED' AFTER `password_hash`;

-- 2. 角色
CREATE TABLE `role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(32)  NOT NULL COMMENT 'ADMIN/MODERATOR/USER',
  `name`        VARCHAR(32)  NOT NULL,
  `description` VARCHAR(255) DEFAULT NULL,
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

-- 3. 权限点
CREATE TABLE `permission` (
  `id`     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`   VARCHAR(48) NOT NULL COMMENT '如 POST:HIDE',
  `name`   VARCHAR(48) NOT NULL,
  `module` VARCHAR(16) NOT NULL COMMENT 'POST/USER/COMMENT/STATS',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限点';

-- 4. 用户-角色
CREATE TABLE `user_role` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `role_id`    BIGINT UNSIGNED NOT NULL,
  `granted_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '授予人（审计）',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_ur_role` (`role_id`),
  CONSTRAINT `fk_ur_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ur_role` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色';

-- 5. 角色-权限
CREATE TABLE `role_permission` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id`       BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
  CONSTRAINT `fk_rp_role` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_rp_perm` FOREIGN KEY (`permission_id`) REFERENCES `permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限';

-- 6. 种子数据
INSERT INTO `role` (`code`,`name`,`description`) VALUES
  ('ADMIN','管理员','全部权限'),
  ('MODERATOR','内容审核','隐藏/删除内容'),
  ('USER','普通用户','仅管理自己的数据');

INSERT INTO `permission` (`code`,`name`,`module`) VALUES
  ('POST:VIEW_ANY','查看任意帖子(含已隐藏)','POST'),
  ('POST:HIDE','隐藏/恢复帖子','POST'),
  ('POST:DELETE','删除帖子','POST'),
  ('COMMENT:DELETE','删除任意评论','COMMENT'),
  ('USER:VIEW_ANY','查看用户列表','USER'),
  ('USER:DISABLE','禁用/启用账号','USER'),
  ('USER:GRANT_ROLE','分配角色','USER'),
  ('STATS:VIEW','查看平台统计','STATS');

INSERT INTO `role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `role` r CROSS JOIN `permission` p WHERE r.code = 'ADMIN';

INSERT INTO `role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `role` r JOIN `permission` p
  ON p.code IN ('POST:VIEW_ANY','POST:HIDE','COMMENT:DELETE') WHERE r.code = 'MODERATOR';
```

> `post` / `comment` 一期就已经有 `status` 列和 `HIDDEN` 取值，所以隐藏帖子**不需要改这两张表**。

---

## B.4 后端实现要点

### B.4.1 认证主体带权限

```java
public record LoginUser(Long id, String username, Set<String> authorities) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities.stream().map(SimpleGrantedAuthority::new).toList();
    }
    // getPassword / isAccountNonExpired ... 返回 null / true
}
```

### B.4.2 权限加载（带缓存）

```java
@Component
@RequiredArgsConstructor
public class PermissionLoader {

    private static final Duration TTL = Duration.ofMinutes(5);
    private final UserRoleRepository userRoleRepository;
    private final StringRedisTemplate redis;

    public Set<String> load(Long userId) {
        String key = "auth:perm:" + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            return new HashSet<>(Arrays.asList(cached.split(",")));
        }
        Set<String> perms = new HashSet<>(userRoleRepository.findPermissionCodesByUserId(userId));
        perms.add("ROLE_USER");
        redis.opsForValue().set(key, String.join(",", perms), TTL);
        return perms;
    }

    public void evict(Long userId) {
        try { redis.delete("auth:perm:" + userId); } catch (Exception ignored) {}
    }
}
```

```java
// UserRoleRepository
@Query("""
   select p.code from UserRole ur
     join RolePermission rp on rp.roleId = ur.roleId
     join Permission p on p.id = rp.permissionId
   where ur.userId = :userId
   """)
List<String> findPermissionCodesByUserId(@Param("userId") Long userId);
```

> **为什么 TTL 是 5 分钟 + 主动删除**：减少每请求查库；同时在"分配角色/禁用"时主动 `DEL`，保证即时生效。

### B.4.3 方法级鉴权

`SecurityConfig` 加 `@EnableMethodSecurity`，接口用**权限点**而不是角色：

```java
@PostMapping("/api/admin/posts/{id}/hide")
@PreAuthorize("hasAuthority('POST:HIDE')")
public ApiResponse<Void> hide(@PathVariable Long id, @AuthenticationPrincipal LoginUser me) { ... }
```

> 用 `hasAuthority` 而非 `hasRole('ADMIN')` 是 RBAC 的关键：接口只声明"需要什么能力"，不关心你是哪个角色。

### B.4.4 管理接口

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/admin/posts` | `POST:VIEW_ANY` |
| POST | `/api/admin/posts/{id}/hide` | `POST:HIDE` |
| POST | `/api/admin/posts/{id}/restore` | `POST:HIDE` |
| DELETE | `/api/admin/posts/{id}` | `POST:DELETE` |
| DELETE | `/api/admin/comments/{id}` | `COMMENT:DELETE` |
| GET | `/api/admin/users` | `USER:VIEW_ANY` |
| POST | `/api/admin/users/{id}/disable` | `USER:DISABLE` |
| POST | `/api/admin/users/{id}/enable` | `USER:DISABLE` |
| GET | `/api/admin/users/{id}/roles` | `USER:VIEW_ANY` |
| PUT | `/api/admin/users/{id}/roles` | `USER:GRANT_ROLE` |
| GET | `/api/admin/roles` | `USER:VIEW_ANY` |
| GET | `/api/admin/stats/overview` | `STATS:VIEW` |

### B.4.5 三个必须做的安全约束

1. **不能禁用自己**：`if (targetId.equals(me.id())) throw new BizException(FORBIDDEN);`
2. **不能撤销最后一个管理员**：移除后若 `COUNT(ADMIN) == 0` 则拒绝，并用 `SELECT ... FOR UPDATE` 防并发。
3. **禁用后立即失效**：登录时校验 `status == ACTIVE`；禁用时删该用户全部 refresh + 拉黑当前 access，并 `permissionLoader.evict(userId)`。

### B.4.6 审计表（推荐）

```sql
CREATE TABLE `admin_audit_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `operator_id` BIGINT UNSIGNED NOT NULL,
  `action`      VARCHAR(48) NOT NULL COMMENT 'POST:HIDE / USER:DISABLE',
  `target_type` VARCHAR(16) NOT NULL,
  `target_id`   BIGINT UNSIGNED NOT NULL,
  `detail`      VARCHAR(500) DEFAULT NULL,
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_audit_op` (`operator_id`,`created_at` DESC),
  KEY `idx_audit_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员操作审计';
```

用 AOP + `@AdminAction("POST:HIDE")` 自动记录，不要在业务代码里手写。

---

## B.5 前端实现要点

```js
// 路由守卫
if (to.meta.permission && !user.hasPermission(to.meta.permission)) {
  ElMessage.warning('无权限访问')
  return { path: '/' }
}
```

```js
// 全局指令 v-permission
app.directive('permission', {
  mounted(el, binding) {
    const user = useUserStore()
    if (!user.hasPermission(binding.value)) el.parentNode?.removeChild(el)
  }
})
// <el-button v-permission="'POST:HIDE'">隐藏</el-button>
```

登录/资料接口增加：

```json
{ "roles": ["ADMIN"], "permissions": ["POST:HIDE", "USER:DISABLE", "..."] }
```

存进 `useUserStore`，供导航栏（是否显示"管理后台"）与 `v-permission` 使用。

> **三重保障**：导航隐藏 + 路由守卫 + 服务端 `@PreAuthorize`。前两个是体验，第三个才是边界。

---

## B.6 学习路径（三步，每步都能独立跑通）

| 步骤 | 做什么 | 学到什么 | 工时 |
|---|---|---|---|
| **S1 单角色** | `app_user` 加 `role`；`@PreAuthorize("hasRole('ADMIN')")`；一个 admin 接口；前端守卫 | JWT 不放角色、`getAuthorities`、`hasRole` 与 `hasAuthority` 的 `ROLE_` 前缀差异 | 半天 |
| **S2 完整 RBAC** | B.3 的 5 张表；`PermissionLoader` + Redis；接口改 `hasAuthority`；`PUT /users/{id}/roles` | 多对多、权限缓存与失效、方法级鉴权 | 1 天 |
| **S3 数据权限（选做）** | 版主只能看自己板块的帖子 | 功能权限 vs 数据权限、动态条件、避免越权 | 半天 |

---

## B.7 实施时对一期代码的改动点

| 文件 | 改动 |
|---|---|
| `db/migration/V2__rbac.sql` | 新增（B.3） |
| `user/entity/AppUser` | 加 `status` |
| `security/LoginUser` | 改为带 `authorities` 的 record |
| `security/JwtAuthenticationFilter` | 每请求装载权限；校验账号状态 |
| `config/SecurityConfig` | 加 `@EnableMethodSecurity`；`/api/admin/**` 需认证 |
| `user/service/AuthService` | 登录校验 `status=ACTIVE` |
| `user/dto/UserVO`、登录响应 | 增加 `roles` / `permissions` |
| 新增 `admin/**` 包 | B.4.4 的接口 |
| `Front/src/router/index.js` | `/admin/**` + `meta.permission` |
| `Front/src/stores/user.js` | `roles` / `permissions` / `hasPermission()` |
| `Front/src/directives/permission.js` | `v-permission` |

**改动很小**，因为一期已预留：`post.status` 有 `HIDDEN`、Service 层统一 `requireOwned*` 校验、错误码只追加不改号。

---

## B.8 自测题

1. **为什么不用单角色字段？** → 角色会爆炸，权限组合无法表达。
2. **为什么权限不进 JWT？** → 签发后不可改，撤权要等过期；正确做法是服务端按需加载 + 缓存。
3. **权限缓存的 TTL 与主动删除怎么配合？** → TTL 兜底（5 分钟），变更时主动删保证即时生效。
4. **`hasRole` 与 `hasAuthority` 的区别？** → `hasRole('ADMIN')` 自动匹配 `ROLE_ADMIN`；`hasAuthority` 精确匹配字符串，RBAC 应该用后者。
5. **最后两个安全约束为什么必须做？** → 防自锁（禁用自己）和防"管理员被清空"导致系统无人可管。
