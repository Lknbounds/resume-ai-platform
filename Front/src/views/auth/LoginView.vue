<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  登录页。用户输入用户名和密码，成功后进入首页。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：一次登录点击的完整过程】
 *
 *      用户点"登录"
 *         ↓
 *      submit()
 *         ├─ formRef.value.validate()   校验表单（前端先挡一轮）
 *         ↓
 *      user.login(form)                 调 store
 *         ├─ authApi.login(form)       → POST /api/auth/login
 *         │     ↓  后端 AuthService.login
 *         │     返回 {accessToken, refreshToken, expiresIn, user}
 *         ├─ setTokens()               存 state + localStorage
 *         └─ profile = data.user
 *         ↓
 *      router.replace(redirect || '/')  跳转
 *         ↓
 *      导航栏显示"欢迎，Alice"
 * ═══════════════════════════════════════════════════════════════════════
 */
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

/**
 * 表单引用。
 *
 * ★ 为什么要 ref？
 *   为了调用 formRef.value.validate() 手动触发校验。
 *
 * ★ 类型为什么是 ref() 而不是 ref({})？
 *   因为它装的是组件实例，初始值是 undefined。
 *   用 ref() 表示"以后会赋值为一个 DOM/组件引用"。
 *   （Vue 3.5+ 也可以写 useTemplateRef('formRef')）
 */
const formRef = ref()

/** 按钮 loading 状态，防止重复点击 */
const loading = ref(false)

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【表单数据】用 reactive 而不是 ref
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ reactive vs ref：
 *   reactive(obj) → 用于对象，访问时直接 form.username
 *   ref(value)    → 用于基本类型，访问时要 .value，如 username.value
 *
 * ★ 表单是个对象，用 reactive 更自然（不用到处 .value）。
 *
 * ★ 为什么不用普通的 const form = {}？
 *   因为普通对象不是响应式的 —— v-model 改了值，界面不会更新。
 *   reactive 让整个对象变成响应式的。
 */
const form = reactive({
  username: '',
  password: ''
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【校验规则】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 这些规则和后端 LoginRequest 的 @NotBlank 对应。
 *
 * 【为什么要前后端都校验？】
 *
 *   前端校验 → 用户体验：不用等网络往返就能看到"请输入用户名"
 *   后端校验 → 安全边界：用户可以直接调接口绕过前端
 *
 * ★ 前端校验是【体验】，后端校验是【安全】。两者都不能省。
 *
 * ★ 登录这里只校验"非空"，和后端一致 ——
 *   不校验长度/格式，是为了不给攻击者额外信息（见后端 LoginRequest 的说明）。
 */
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【提交登录】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 为什么方法签名是 async？
 *   因为里面有 await（校验、登录都是异步的）。
 */
async function submit() {
  /*
   * 【第一步：前端校验】
   *
   * ★ validate() 返回 Promise：
   *     校验通过 → resolve
   *     校验失败 → reject
   *
   * ★ 所以这里不加 try-catch 的话，校验失败会抛出未捕获的 Promise rejection。
   *   在 Vue 里会打印一个警告。虽然不影响功能，但控制台很脏。
   *
   *   ★ 更规范的写法：
   *     const valid = await formRef.value.validate().catch(() => false)
   *     if (!valid) return
   *
   *   本项目选简单写法（直接 await），因为校验失败的 reject
   *   被 async 函数捕获后变成 unhandled rejection，
   *   实际表现是"什么都没发生"（用户看到红字提示），体验是对的。
   */
  await formRef.value.validate()

  loading.value = true

  try {
    /*
     * 【第二步：调登录】
     *
     * ★ 失败时（用户名密码错）会发生什么？
     *   http.js 的拦截器会 ElMessage.error("用户名或密码错误") 并 reject。
     *   所以这里不需要再弹提示 —— 拦截器已经弹过了。
     *
     *   异常会一路抛到这里，被 finally 捕获（loading 复位），
     *   然后继续往外抛 —— 但因为这个函数是事件处理器，
     *   抛出后 Vue 会打印到控制台，用户看不到任何变化（除了错误提示）。
     */
    await user.login(form)

    ElMessage.success('登录成功')

    /*
     * 【第三步：跳转】
     *
     * ★ route.query.redirect 从哪来？
     *   router 守卫里：
     *       return { path: '/login', query: { redirect: to.fullPath } }
     *   记录用户原本想去的页面。
     *
     * ★ 为什么用 replace 而不是 push？
     *   push 会在历史记录里加一条 /login。
     *   用户点浏览器"后退"会回到登录页 —— 这很怪（刚登录完又看到登录页）。
     *
     *   replace 替换掉当前历史记录，后退会回到登录页【之前】的页面。
     *   ★ 这是登录成功后的标准做法。
     */
    router.replace(route.query.redirect || '/')
  } finally {
    /*
     * ★ finally 保证无论成功失败都复位 loading。
     *   如果放在 try 里，失败时按钮会永远转圈。
     */
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card" shadow="never">
      <div class="auth-head">
        <!-- ★ 品牌符号：和导航栏保持一致 -->
        <div class="logo-wrap">
          <span class="logo">简</span>
        </div>
        <h2>欢迎回来</h2>
        <p>登录后开始分析你的简历</p>
      </div>

      <!--
        ★ :model="form"     绑定数据源
        ★ :rules="rules"    绑定校验规则
        ★ ref="formRef"     拿到组件实例，才能调 validate()
        ★ @keyup.enter      在表单任意位置按回车都触发提交
                            （比只给按钮加点击事件方便）
      -->
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="submit"
      >
        <el-form-item label="用户名" prop="username">
          <!--
            ★ v-model="form.username"  双向绑定：
               输入框改 → form.username 改
               form.username 改 → 输入框显示变
            ★ 为什么要写 prop="username"？
              这是告诉 el-form-item "我对应 form 里的哪个字段"。
              没有它，校验规则找不到该作用于哪个字段。
          -->
          <el-input
            v-model="form.username"
            placeholder="4-32 位字母数字下划线"
            size="large"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <!--
            ★ type="password"    输入内容显示为圆点
            ★ show-password      右侧出现"眼睛"图标，可以切换明文
          -->
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 8 位，含字母和数字"
            size="large"
          />
        </el-form-item>

        <!--
          ★ :loading="loading"  按钮显示转圈，并且自动禁用防止重复点击
        -->
        <el-button
          type="primary"
          :loading="loading"
          class="full"
          size="large"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>

      <div class="auth-foot">
        还没有账号？
        <!-- ★ el-link 是一个看起来像超链接的按钮，比 <a href> 好 -->
        <el-link type="primary" @click="router.push('/register')">
          立即注册
        </el-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
/* ★ scoped：样式只作用于当前组件，不会污染其他页面 */
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
}

.auth-card {
  width: 400px;
  border-radius: var(--radius-lg);
  padding: 8px;
}

.auth-head {
  text-align: center;
  margin-bottom: 24px;
}

/*
 * ★ 登录页顶部的品牌符号
 *   和导航栏的 logo 完全一致 —— 视觉上建立"这是同一个产品"的认知
 */
.logo-wrap {
  display: flex;
  justify-content: center;
  margin-bottom: 18px;
}

.logo {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  background: var(--gradient-brand);
  box-shadow: 0 6px 20px rgba(41, 163, 153, 0.28);

  display: flex;
  align-items: center;
  justify-content: center;

  color: #fff;
  font-size: 20px;
  font-weight: 700;
}

.auth-head h2 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text);
}

.auth-head p {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
}

.full {
  width: 100%;
  margin-top: 8px;
}

.auth-foot {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted);
}
</style>
