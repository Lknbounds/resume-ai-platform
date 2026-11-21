<script setup>
/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个页面是什么】
 *  注册页。填用户名、密码、昵称，提交后【自动登录】并进首页。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向】
 *
 *      用户点"注册"
 *         ↓
 *      submit()
 *         ├─ 前端校验（含"两次密码是否一致"）
 *         ↓
 *      user.register(form)
 *         ├─ authApi.register(...)   → POST /api/auth/register（创建用户）
 *         ├─ authApi.login(...)      → POST /api/auth/login（拿 token）
 *         └─ 存 token + profile
 *         ↓
 *      router.replace('/')           进首页
 *
 *  ★ 为什么注册后要再调一次登录？
 *    后端注册接口只返回 UserVO（不返回 token）——
 *    注册就是注册，登录就是登录，职责分开。
 *    前端用一行代码（再调一次 login）换来后端的简洁。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const user = useUserStore()

const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: ''
})

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【自定义校验：确认密码】
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★ 为什么这个校验要自己写？
 *   因为 el-form 的规则是"针对单个字段"的，
 *   而"两个字段是否相等"需要跨字段访问 —— 内置规则做不到。
 *
 * ★ 参数说明：
 *   rule      → 当前规则的配置（这里用不到）
 *   value     → 当前字段的值（即 confirmPassword）
 *   callback  → 校验结果回调。不调用它，校验会一直"进行中"
 *                callback()        表示通过
 *                callback(new Error('...'))  表示失败
 *
 * ★ 为什么要用 callback 而不是 return / throw？
 *   这是 Element Plus（底层是 async-validator）的约定。
 *   早期的 validator 写法就是这样，新版本也支持返回 Promise，
 *   但 callback 版本兼容性最好。
 *
 * ★ 注意 form.password 的判断：
 *   如果用户先填确认密码、后填密码，确认密码的校验不会自动重跑。
 *   所以实际体验中，应该给 password 字段加 trigger: 'blur' 时
 *   重新触发 confirmPassword 的校验。
 *   本项目简化处理（用户一般按顺序填），体验可接受。
 */
function validateConfirm(rule, value, callback) {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【校验规则】必须和后端 RegisterRequest 的注解一一对应
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ★★ 为什么规则必须和后端一致？
 *
 *   如果前端宽松、后端严格：
 *      用户填了 3 位用户名，前端说"可以"，提交后后端报"用户名太短"——
 *      用户白填了，体验差。
 *
 *   如果前端严格、后端宽松：
 *      用户能绕过后端（直接调接口），规则形同虚设。
 *
 *   ★ 所以两边规则要同步。改规则时两个文件都要改。
 *     （见后端 dto/RegisterRequest.java）
 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 32, message: '长度必须在 4-32 位之间', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]+$/,
      message: '只能包含字母、数字、下划线',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 32, message: '长度必须在 8-32 位之间', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
      message: '必须同时包含字母和数字',
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    /*
     * ★ 自定义校验函数的写法：
     *   { validator: 函数名, trigger: 'blur' }
     */
    { validator: validateConfirm, trigger: 'blur' }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 16, message: '最长 16 个字符', trigger: 'blur' }
  ]
}

async function submit() {
  await formRef.value.validate()
  loading.value = true

  try {
    /*
     * ★ 注意这里只传三个字段，不传 confirmPassword。
     *   因为后端 RegisterRequest 里没有这个字段。
     *   如果传了，Jackson 会忽略（默认行为），但没必要传。
     *
     *   ★ 显式构造对象比 {...form} 更安全 ——
     *     万一以后 form 里加了内部字段（比如"记住我"），
     *     不会误传给后端。
     */
    await user.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname
    })

    ElMessage.success('注册成功，已自动登录')

    /*
     * ★ 用 replace 不用 push：注册+登录后，
     *   用户按后退不应该回到注册页。
     */
    router.replace('/')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card" shadow="never">
      <div class="auth-head">
        <div class="logo-wrap">
          <span class="logo">简</span>
        </div>
        <h2>创建账号</h2>
        <p>注册后即可分析简历、发布互评</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="4-32 位字母数字下划线"
            size="large"
          />
        </el-form-item>

        <el-form-item label="昵称" prop="nickname">
          <el-input
            v-model="form.nickname"
            placeholder="展示给其他用户的名字"
            maxlength="16"
            show-word-limit
            size="large"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 8 位，含字母和数字"
            size="large"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            placeholder="再次输入密码"
            size="large"
          />
        </el-form-item>

        <el-button
          type="primary"
          :loading="loading"
          class="full"
          size="large"
          @click="submit"
        >
          注册
        </el-button>
      </el-form>

      <div class="auth-foot">
        已有账号？
        <el-link type="primary" @click="router.push('/login')">去登录</el-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
  padding: 24px;
}

.auth-card {
  width: 400px;
  border-radius: var(--radius-lg);
  padding: 8px;
}

.auth-head {
  text-align: center;
  margin-bottom: 20px;
}

.logo-wrap {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
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
