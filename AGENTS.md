# AGENTS.md

本仓库的协作约定，供 AI 助手与协作者遵循。

## 提交信息

**所有分支的 commit message 一律使用英文。**

- 标题行使用 Conventional Commits 格式：`feat:` / `fix:` / `docs:` / `build:` / `refactor:` / `chore:` / `test:`
- 标题使用祈使句，首字母小写，不超过 72 字符
- 正文说明动机与影响，可使用英文段落
- 不使用 `fix test`、`update` 之类无信息量的标题

示例：

```text
docs(poc): record YukiHookAPI 1.5.0-beta.4 migration findings

Verify findings against the packaged AAR bytecode and sources jar
instead of the outdated website documentation.
```

## 分支

- 探索性工作单独开分支，不污染 `main`
- 分支名可使用中文（如 `Poc探索`），但 commit message 仍用英文
