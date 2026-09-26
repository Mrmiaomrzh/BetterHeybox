# Telegram 通知配置

CI、Release 与故障排查都会通过 Bot API 把版本信息、更新日志、commit 链接和 APK 推到 Telegram。
发送逻辑集中 [`.github/scripts/telegram-notify.sh`](scripts/telegram-notify.sh)。

## 推送时间

| 工作流 | 触发条件 | 目标会话 | 内容 |
| --- | --- | --- | --- |
| [CI](workflows/ci.yml) | push 到 `main` 且构建成功 | `TELEGRAM_CI_CHAT_ID` | CI 版本号、commit / compare 链接、运行链接、debug APK |
| [Release](workflows/release.yml) | 手动运行且发版成功（`notify_telegram=true`） | `TELEGRAM_CHAT_ID` | 更新日志（`CHANGELOG.md` 对应版本小节）、带链接的提交记录、Release 链接、release APK |
| [Telegram test](workflows/telegram-test.yml) | 手动运行 | 按 `target` 选择 | 固定测试消息 + 一个小测试文件 |

非 `main` 分支的 push 只上传 Actions 产物，不推送 Telegram。

## 仓库 Secrets / Variables

在 **Settings → Secrets and variables → Actions** 配置：

| 名称 | 类型 | 说明 |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | Secret | BotFather token，工作流共用 |
| `TELEGRAM_CHAT_ID` | Secret | Release 目标 |
| `TELEGRAM_CI_CHAT_ID` | Secret | CI 目标 |
| `TELEGRAM_MESSAGE_THREAD_ID` | Secret（可选） | 论坛群的话题 id |
| `TELEGRAM_CI_SILENT` | Variable（可选） | 设为 `1` 时 CI 通知静音发送（`disable_notification`） |

### 拿 chat_id / thread_id

1. 把 Bot 拉进目标群 / 频道，**频道必须把 Bot 设为管理员并勾选「发布消息」**；论坛群还要给「管理话题」权限。
2. 在群里发一条消息（话题里则在该话题内发），然后访问：

   ```
   https://api.telegram.org/bot<TOKEN>/getUpdates
   ```

   在 `result[].message.chat.id` 取 `chat_id`；话题消息额外有 `message_thread_id`。
3. 频道也可以用公开用户名 `@Betterheybox`，（需 Bot 是管理员）。

## 行为约定

- **未配置 secret 时静默跳过**（输出 `::notice::` 并以 0 退出），fork 或未配置的仓库不会因此失败。
- **通知失败不影响构建与发版**：Release 通知步骤在发布之后执行，`continue-on-error: true`，失败只留下 `::error::` 注解。
- 正文超过 3900 字符按「整行边界」截断并追加「完整内容见 GitHub」，不会截断 HTML 标签；文件说明上限 1000 字符。
- 附件超过 49 MB 时只发正文并给出 warning（Bot 上传上限 50 MB；当前 APK 约 2 MB）。
- 429 / 网络错误最多重试 3 次，遵循响应里的 `retry_after`（上限 60s）。
- token 全程 `::add-mask::`。

## 常见错误

| 报错 | 原因 / 处理 |
| --- | --- |
| `chat not found` | `chat_id` 写错，或 Bot 不在该会话里 |
| `not enough rights to send documents to the chat` | 频道 / 群未把 Bot 设为管理员，或缺少发帖权限 |
| `message thread not found` | `TELEGRAM_MESSAGE_THREAD_ID` 不是该群的话题 id |
| `can't parse entities` | 消息里的 HTML 标签不合法（模板只允许 `b` / `i` / `a` / `code` / `pre` / `u` / `s`） |
| `Unauthorized` (401) | `TELEGRAM_BOT_TOKEN` 无效或已 revoke |
| `Too Many Requests: retry after N` | 触发限流（群 / 频道约 20 条/分钟），脚本会自动退避重试 |
