<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  个人资料页：查看/修改昵称、目标岗位、经验年限；修改密码。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      进入页面
 *         ↓
 *      onMounted：从 store 拿 profile 填进表单
 *         ↓
 *      用户改昵称 → 点"保存"
 *         ↓
 *      save()
 *         ├─ userApi.updateProfile(form)  → PUT /api/users/me
 *         │     ↓ 后端 UserService.updateProfile
 *         │     返回更新后的 UserVO
 *         └─ user.profile = 返回值         ← 界面立刻更新
 *
 *      用户点"修改密码" → 填旧密码+新密码 → 确认
 *         ↓
 *      changePassword()
 *         ├─ userApi.changePassword(pwd)  → PUT /api/users/me/password
 *         │     ↓ 后端：验证旧密码 → 改密 → 清空所有 refresh token
 *         ├─ user.clear()                 清本地 token
 *         └─ window.location = '/login'   跳登录页
 * ═══════════════════════════════════════════════════════════════════════
 */
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import BackButton from '@/components/BackButton.vue'
import { useUserStore } from '@/stores/user'
import { userApi } from '@/api/user'

const user = useUserStore()

/** 保存按钮的 loading */
const saving = ref(false)

/**
 * ★ 表单数据和 user.profile 分开维护（不直接 v-model 到 store）。
 *
 * 【★ 为什么？】
 *   如果直接 v-model="user.profile.nickname"：
 *      - 用户一边打字，右上角的昵称就一边变 —— 看着很怪
 *      - 用户改了一半点"取消"，没法恢复（store 已经被改了）
 *      - 保存失败时，store 里是错的数据
 *
 *   用本地 form 的好处：
 *      - 改动只在表单里，点保存才提交
 *      - 保存成功后把返回值写回 store，两边一致
 *      - 可以随时放弃修改（重新从 profile 填一次即可）
 *
 *   ★ 这是"表单编辑"的标准模式：编辑态和展示态分离。
 */
const form = reactive({
  nickname: '',
  targetPosition: '',
  yearsOfExperience: 0
})

/**
 * 修改密码弹窗。
 */
const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwd = reactive({
  oldPassword: '',
  newPassword: ''
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【进入页面时填充表单】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 用 async 的 onMounted，内部 await 拉资料。
 *
 * ★ 为什么还要判断 user.profile 是否为空？
 *   router 守卫已经拉过了，但：
 *     1. 守卫里 catch 掉异常了（后端没起时不阻塞导航）
 *     2. 万一那次没拉到，这里是第二道保险
 *
 * ★ ?? 是"空值合并运算符"：
 *     a ?? b  → a 是 null 或 undefined 时用 b，否则用 a
 *   比 a || b 更准确（|| 会在 a 是 0、''、false 时也用 b，
 *   而 0 对 yearsOfExperience 是合法值！）
 */
onMounted(async () => {
  const p = user.profile || (await user.fetchProfile())

  form.nickname = p.nickname
  form.targetPosition = p.targetPosition || ''
  form.yearsOfExperience = p.yearsOfExperience ?? 0
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【保存资料】
 * ═══════════════════════════════════════════════════════════════════════
 */
async function save() {
  saving.value = true
  try {
    /*
     * ★ 为什么把返回值赋回 store？
     *   因为后端可能对数据做了处理（比如 trim、默认值），
     *   返回的才是"真实存下来的样子"。
     *   直接赋值保证界面显示的和数据库里的一致。
     */
    user.profile = await userApi.updateProfile({
      nickname: form.nickname,
      /*
       * ★ targetPosition 传空字符串而不是 null。
       *   因为后端字段是 String，空字符串表示"清空"。
       *   如果传 null，JSON 里就没有这个字段，
       *   后端拿到 null —— 也能清空，但显式传 '' 意图更明确。
       */
      targetPosition: form.targetPosition,
      yearsOfExperience: form.yearsOfExperience
    })

    ElMessage.success('已保存')

    /*
     * ★ 右上角的昵称会立即更新吗？
     *   会。因为 DefaultLayout 里用的是 user.displayName 这个 getter，
     *   它依赖 user.profile.nickname —— profile 变了，界面自动重渲染。
     *
     *   ★ 这就是响应式的好处：不用手动刷新任何东西。
     */
  } finally {
    saving.value = false
  }
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【修改密码】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★★ 这是一个"改完必须踢下线"的操作，前端的配合很关键。
 */
async function changePassword() {
  /*
   * ★ 为什么前端也校验一遍新密码规则？
   *   用户体验：不用等网络往返就能看到错误。
   *   后端仍会再校验一次（安全边界）。
   */
  if (pwd.newPassword.length < 8) {
    return ElMessage.warning('新密码至少 8 位')
  }
  if (!/^(?=.*[A-Za-z])(?=.*\d).+$/.test(pwd.newPassword)) {
    return ElMessage.warning('新密码必须同时包含字母和数字')
  }

  pwdSaving.value = true
  try {
    await userApi.changePassword({
      oldPassword: pwd.oldPassword,
      newPassword: pwd.newPassword
    })

    /*
     * ★★ 成功后必须做这两件事：
     *
     *   1. 清空本地 token
     *      后端已经把所有 refresh token 清了，
     *      当前的 access 虽然还能用 2 小时，但无法续期。
     *      留着它只会让用户"用一会儿突然被踢"。
     *
     *   2. 跳登录页
     *      引导用户用新密码重新登录。
     *
     * ★ 为什么不只是 alert 提示然后停留在页面？
     *   因为用户接下来任何操作都可能因为 token 失效而失败，
     *   不如主动引导到登录页。
     */
    ElMessage.success('密码已修改，请重新登录')
    user.clear()
    window.location.href = '/login'
  } finally {
    /*
     * ★ 这里 finally 其实执行不到（上面页面已经跳走了）。
     *   但保留是好习惯：如果哪天改成不跳转，也不会漏掉复位。
     */
    pwdSaving.value = false
  }
}
</script>

<template>
  <DefaultLayout>
    <BackButton to="/" label="返回首页" />

    <div class="profile-page">
      <header class="studio-page-head">
        <div class="studio-page-copy">
          <p class="studio-eyebrow">PROFILE</p>
          <h1 class="studio-page-title">个人资料</h1>
          <p class="studio-page-sub">让分析与建议更贴近你的岗位目标和经验阶段。</p>
        </div>
      </header>

      <div class="profile-grid">
        <aside class="studio-paper identity-card">
          <div class="identity-top">
            <span class="avatar" aria-hidden="true">
              {{ user.displayName?.slice(0, 1) || '简' }}
            </span>
            <span class="member-label">CAREER STUDIO MEMBER</span>
          </div>

          <h2>{{ user.displayName || '未设置昵称' }}</h2>
          <p class="username">@{{ user.profile?.username || '-' }}</p>

          <dl class="identity-facts">
            <div>
              <dt>目标岗位</dt>
              <dd>{{ form.targetPosition || '尚未设置' }}</dd>
            </div>
            <div>
              <dt>经验年限</dt>
              <dd>{{ form.yearsOfExperience ?? 0 }} 年</dd>
            </div>
          </dl>

          <div class="identity-foot">
            <p><span>加入时间</span>{{ user.profile?.createdAt || '-' }}</p>
            <p><span>最近登录</span>{{ user.profile?.lastLoginAt || '-' }}</p>
          </div>
        </aside>

        <section class="studio-paper settings-card">
          <header class="settings-head">
            <div>
              <p class="panel-kicker">PROFILE DETAILS</p>
              <h2>编辑资料</h2>
            </div>
            <span class="settings-note">用户名作为登录凭证，暂不支持修改</span>
          </header>

          <el-form label-position="top" class="profile-form">
            <div class="form-grid">
              <el-form-item label="用户名">
                <el-input :model-value="user.profile?.username" disabled />
              </el-form-item>

              <el-form-item label="昵称">
                <el-input v-model="form.nickname" maxlength="16" show-word-limit />
              </el-form-item>

              <el-form-item label="目标岗位">
                <el-input
                  v-model="form.targetPosition"
                  placeholder="如：Java 后端开发工程师"
                />
              </el-form-item>

              <el-form-item label="经验年限">
                <div class="experience-field">
                  <el-input-number v-model="form.yearsOfExperience" :min="0" :max="50" />
                  <span class="unit">年</span>
                </div>
              </el-form-item>
            </div>

            <div class="form-actions">
              <el-button type="primary" :loading="saving" @click="save">
                保存资料
              </el-button>
            </div>
          </el-form>

          <div class="security-row">
            <div>
              <p class="security-kicker">ACCOUNT SECURITY</p>
              <h3>账户安全</h3>
              <p>修改密码后，所有设备都需要重新登录。</p>
            </div>
            <el-button @click="pwdVisible = true">修改密码</el-button>
          </div>
        </section>
      </div>
    </div>

    <el-dialog v-model="pwdVisible" title="修改密码" width="420px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="修改成功后，所有设备都会被强制下线"
        style="margin-bottom: 16px"
      />

      <el-form label-position="top">
        <el-form-item label="原密码">
          <el-input v-model="pwd.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="pwd.newPassword"
            type="password"
            show-password
            placeholder="至少 8 位，含字母和数字"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="changePassword">
          确认修改
        </el-button>
      </template>
    </el-dialog>
  </DefaultLayout>
</template>

<style scoped>
.profile-page { max-width: 1040px; padding-bottom: 48px; }

.profile-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.78fr) minmax(0, 1.62fr);
  align-items: start;
  gap: 16px;
}

.identity-card {
  position: relative;
  overflow: hidden;
  padding: 25px;
  background:
    radial-gradient(circle at 92% 7%, rgba(242, 193, 91, 0.26), transparent 8rem),
    var(--brand);
  color: #fff;
}

.identity-card::after {
  content: '';
  position: absolute;
  width: 88px;
  height: 88px;
  right: -38px;
  bottom: -38px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 50%;
}

.identity-top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  display: inline-flex;
  width: 48px;
  height: 48px;
  align-items: center;
  justify-content: center;
  border-radius: 14px 14px 14px 4px;
  background: var(--accent);
  color: #fff;
  font-family: var(--font-serif);
  font-size: 22px;
  box-shadow: 5px 5px 0 rgba(0, 0, 0, 0.08);
}

.member-label {
  color: rgba(255, 255, 255, 0.62);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.1em;
}

.identity-card h2 {
  margin: 29px 0 3px;
  font-family: var(--font-serif);
  font-size: 27px;
  line-height: 1.35;
}

.username {
  margin: 0;
  color: rgba(255, 255, 255, 0.62);
  font-size: 12px;
}

.identity-facts {
  display: grid;
  gap: 1px;
  margin: 24px 0 0;
  border-radius: 12px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.11);
}

.identity-facts div {
  padding: 13px 14px;
  background: rgba(23, 58, 48, 0.28);
}

.identity-facts dt {
  color: rgba(255, 255, 255, 0.58);
  font-size: 10px;
}

.identity-facts dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  color: #fff;
  font-size: 13px;
  font-weight: 650;
}

.identity-foot {
  display: grid;
  gap: 8px;
  margin-top: 22px;
  padding-top: 17px;
  border-top: 1px solid rgba(255, 255, 255, 0.16);
}

.identity-foot p {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  color: rgba(255, 255, 255, 0.78);
  font-size: 10.5px;
}

.identity-foot span { color: rgba(255, 255, 255, 0.52); }

.settings-card { overflow: hidden; }

.settings-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding: 22px 24px 17px;
  border-bottom: 1px solid var(--line-soft);
}

.panel-kicker,
.security-kicker {
  margin: 0 0 5px;
  color: var(--accent-deep);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.settings-head h2,
.security-row h3 {
  margin: 0;
  color: var(--text);
  font-family: var(--font-serif);
  font-size: 22px;
  letter-spacing: -0.025em;
}

.settings-note {
  max-width: 260px;
  color: var(--text-faint);
  font-size: 10.5px;
  line-height: 1.6;
  text-align: right;
}

.profile-form { padding: 22px 24px 24px; }

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 18px;
}

.experience-field {
  display: flex;
  align-items: center;
}

.unit {
  margin-left: 9px;
  color: var(--text-muted);
  font-size: 13px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 3px;
}

.security-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 22px;
  padding: 20px 24px;
  border-top: 1px solid var(--line-soft);
  background: var(--surface-deep);
}

.security-row h3 { font-size: 18px; }

.security-row p:not(.security-kicker) {
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 11.5px;
  line-height: 1.6;
}

@media (max-width: 820px) {
  .profile-grid { grid-template-columns: 1fr; }
  .identity-card { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }
  .identity-top, .identity-card h2, .username { grid-column: 1; }
  .identity-facts { grid-column: 2; grid-row: 1 / span 3; margin-top: 0; }
  .identity-foot { grid-column: 1 / -1; }
}

@media (max-width: 620px) {
  .identity-card { display: block; padding: 22px; }
  .identity-facts { margin-top: 22px; }
  .settings-head { align-items: flex-start; flex-direction: column; padding: 19px 18px 16px; }
  .settings-note { text-align: left; }
  .profile-form { padding: 18px; }
  .form-grid { grid-template-columns: 1fr; }
  .form-actions :deep(.el-button) { width: 100%; }
  .security-row { align-items: stretch; flex-direction: column; padding: 18px; }
  .security-row :deep(.el-button) { width: 100%; }
}
</style>