# GitHub 上传命令与注意事项

本项目已上传到：

```text
https://github.com/Lknbounds/resume-ai-platform
```

---

## 一、上传前必须检查

### 1. 不要上传隐私简历

以下目录已经加入 `.gitignore`，不能提交：

```text
ResumeIQ/
```

里面有大量简历 PDF，可能包含姓名、手机号、邮箱、学校、工作经历等个人隐私。

### 2. 不要上传密钥和密码

不要提交：

```text
.env
application-local.yml
DASHSCOPE_API_KEY
JWT_SECRET
数据库真实密码
GitHub Token
```

### 3. 不要上传构建产物和依赖

```text
BackEnd/target/
Front/node_modules/
Front/dist/
tools/
工具安装包/
dump.rdb
tmp/
```

### 4. 提交前检查暂存文件

```powershell
git status
git diff --cached --name-only
```

确认没有以下内容：

```text
简历 PDF
数据库导出文件
API Key
Token
密码
个人信息
```

---

## 二、第一次上传 GitHub

### 1. 初始化 Git

```powershell
cd E:\简历评价项目
git init
```

作用：

```text
git init
在当前项目中创建 Git 仓库。
```

### 2. 添加文件

```powershell
git add .
```

作用：

```text
git add .
把当前目录下所有未忽略的文件加入 Git 暂存区。
.gitignore 中的文件不会添加。
```

### 3. 查看将要提交的文件

```powershell
git status
```

作用：

```text
git status
查看哪些文件被修改、添加或删除。
```

### 4. 提交到本地仓库

```powershell
git commit -m "feat: resume analysis platform"
```

作用：

```text
git commit
把暂存区文件保存为一个本地提交。

-m "说明"
填写本次提交的说明。
```

### 5. 创建 GitHub 仓库

在 GitHub 网站创建空仓库：

```text
Repository name: resume-ai-platform
Visibility: Public
不要勾选 Initialize with README
```

不要勾选 README，因为本地已经有 README，否则推送时可能发生冲突。

### 6. 关联远程仓库

```powershell
git remote add origin https://github.com/Lknbounds/resume-ai-platform.git
```

作用：

```text
git remote add origin ...
把本地仓库和 GitHub 仓库关联起来。

origin
是远程仓库的默认名称。
```

### 7. 修改主分支名称

```powershell
git branch -M main
```

作用：

```text
把当前分支重命名为 main。
GitHub 当前默认主分支一般使用 main。
```

### 8. 上传代码

```powershell
git push -u origin main
```

作用：

```text
git push
把本地提交上传到 GitHub。

-u
建立本地 main 和远程 main 的追踪关系。

origin main
上传到 origin 远程仓库的 main 分支。
```

第一次可能弹出 GitHub 登录窗口。

---

## 三、以后更新代码

```powershell
git status
git add .
git commit -m "feat: 更新简历修改建议"
git push
```

以后推送不需要再写：

```powershell
-u origin main
```

因为第一次推送已经建立了追踪关系。

---

## 四、常用 Git 命令

```powershell
# 查看状态
git status

# 查看当前分支
git branch

# 查看远程仓库
git remote -v

# 查看提交记录
git log --oneline

# 查看修改内容
git diff

# 撤销未暂存的修改
git restore 文件路径

# 从暂存区移除文件，但不删除本地文件
git restore --staged 文件路径
```

---

## 五、提交信息建议

推荐格式：

```text
feat: 新增功能
fix: 修复问题
docs: 修改文档
refactor: 重构代码
test: 添加测试
chore: 构建或配置调整
```

示例：

```powershell
git commit -m "feat: 简历双栏修改工作台"
git commit -m "fix: 修复 PDF 解析顺序"
git commit -m "docs: 补充 Docker 部署教程"
```

---

## 六、上传后检查

打开：

```text
https://github.com/Lknbounds/resume-ai-platform
```

检查：

1. `main` 分支存在；
2. README 正常显示；
3. BackEnd、Front、文档目录存在；
4. `ResumeIQ` 没有上传；
5. `tools` 没有上传；
6. `.env` 没有上传；
7. GitHub 页面没有出现密钥警告；
8. 仓库是 Public 或 Private，符合你的预期。