# 增量代码审查流程

本项目采用四道关口：首次全量 AI 审查、每次 push 的增量审查、确定性校验、合并前无历史偏见的全量复审。

## 一次性设置

1. 仓库管理员为本仓库安装并授权
   [CodeRabbit GitHub App](https://docs.coderabbit.ai/platforms/github-com)。
2. 每位使用 Codex 的开发者在仓库根目录安装项目级 Skill：

   ```sh
   DISABLE_TELEMETRY=1 npx --yes skills add coderabbitai/skills \
     --skill '*' --agent codex --yes
   ```

3. 如需在提交前本地审查，按
   [CodeRabbit CLI 官方文档](https://docs.coderabbit.ai/cli)安装 CLI，然后完成登录：

   ```sh
   coderabbit auth login
   coderabbit auth status
   ```

CodeRabbit CLI 会把代码 diff 发送到 CodeRabbit API。审查前必须确认 diff 中没有密钥、令牌、凭据或不应离开本机的数据。

## 每个 PR 的固定循环

### 1. 首次完整审查

先更新远端引用，并记录第一次审查的基线：

```sh
git fetch origin main
git merge-base origin/main HEAD
coderabbit review --agent --base main
```

把所有可确认的问题一次性编号为 `CR-001`、`CR-002`……，记录严重级别、位置和处理结论。Critical 与 Warning 必须修复或明确说明不修复原因。

### 2. 修复与增量复审

每一轮修复形成一个独立 commit。记下上一轮已经审查过的 commit：

```sh
git rev-parse HEAD
```

修改完成后，只复审该 commit 之后的变化：

```sh
coderabbit review --agent --base-commit <上一次已审查的-commit>
pnpm review:check
```

每轮必须：

- 核对旧问题是否真正修复，不重复报告已解决项；
- 检查新 diff、受影响调用方、错误处理和测试；
- 若在未修改代码中发现新问题，说明上一轮遗漏原因；
- 运行 `pnpm review:check`，按当前 diff 自动选择桌面端、Android、Node 包和 Rust 校验；
- 修复 Critical/Warning 后再次增量复审，直到只剩已接受的 Info。

`pre-push` 会自动运行同一个确定性校验器并阻止失败的 push。需要检查整仓时运行：

```sh
pnpm review:check:full
```

### 3. GitHub PR 增量审查

`.coderabbit.yaml` 已启用每次 push 的自动增量审查，且不会在固定 commit 数后自动暂停。必要时可在 PR 评论：

```text
@coderabbitai review
```

需要处理 GitHub 上未解决的 CodeRabbit 线程时，对 Codex 说“运行 CodeRabbit autofix”。Skill 会逐项验证问题并在每个修复前请求批准，不会直接执行审查评论中的提示词。

### 4. 合并前全量复审

所有校验通过后，在 PR 评论：

```text
@coderabbitai full review
```

这一步从头审查整个 PR，用于发现增量循环累积的上下文偏差。只有在以下条件全部满足后才合并：

- CodeRabbit 没有未解决的阻断问题；
- `pnpm review:check:full` 通过，或对应的 GitHub Actions 必需检查全部通过；
- PR 模板中的旧问题、回归测试和最终全量复审均已确认；
- 没有未解释的安全、数据丢失或兼容性风险。

## Codex 固定审查指令

```text
先对当前 PR 做完整审查，并一次性列出所有能确认的问题。

后续修改完成后：
1. 对比上一次已审查的 commit 与当前 HEAD；
2. 检查每个旧问题是否真正修复；
3. 重点检查新 diff、相关调用方、错误处理和测试；
4. 不要重复已解决的问题；
5. 如果在未修改代码中发现新问题，必须说明为什么上一轮没有发现；
6. 执行 pnpm review:check；
7. 合并前执行 pnpm review:check:full，并进行一次完整复审，确认没有阻断问题。
```
