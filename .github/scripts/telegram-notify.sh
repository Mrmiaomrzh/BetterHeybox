#!/usr/bin/env bash
#
# BetterHeybox Telegram 通知发送器（CI / Release / Telegram test 共用）
#
# 用法:
#   telegram-notify.sh --message FILE [--document FILE] [--caption FILE]
#                      [--limit N] [--truncate-mark TEXT] [--silent]
#
# 环境变量:
#   TELEGRAM_BOT_TOKEN           Bot Token（必填；未配置则跳过，退出码 0）
#   TELEGRAM_CHAT_ID             目标会话：@channel 或 -100xxxxxxxxxx（必填；未配置则跳过）
#   TELEGRAM_MESSAGE_THREAD_ID   可选，论坛群的话题 id
#   TELEGRAM_SILENT=true         静音发送（disable_notification）
#   TELEGRAM_DRY_RUN=true        只打印摘要与正文，不真的发送
#
# 退出码: 0 = 已发送 / 按配置跳过；1 = 发送失败；2 = 参数错误
#
# 约定:
#   - 正文按整行边界截断（默认 3900 字符，Bot API 上限 4096），避免截断 HTML 标签
#   - 先发正文，再发文件；文件说明上限 1024 字符，同样按行截断
#   - 429 / 网络错误最多重试 3 次，遵循响应里的 retry_after（上限 60s）
#   - 附件超过 49 MB（Bot 上传上限 50 MB）时跳过附件并给出 warning
#   - 凭据始终 ::add-mask::，dry-run 只显示打码后的 token 前缀

set -euo pipefail

# 让 bash 按字符（而不是字节）计算长度；仅在调用方未指定 locale 时设置
if [ -z "${LC_ALL:-}" ] && [ -z "${LC_CTYPE:-}" ]; then
  if LC_ALL=C.UTF-8 locale >/dev/null 2>&1; then
    export LC_ALL=C.UTF-8
  fi
fi

MESSAGE_FILE=""
DOCUMENT_FILE=""
CAPTION_FILE=""
LIMIT=3900
TRUNCATE_MARK=$'\n…（内容过长已截断，完整内容见 GitHub）'
SILENT=0
MAX_ATTEMPTS=3
DOCUMENT_MAX_BYTES=$((49 * 1024 * 1024))

usage() {
  cat <<'USAGE'
用法: telegram-notify.sh --message FILE [--document FILE] [--caption FILE]
                         [--limit N] [--truncate-mark TEXT] [--silent]
  --message FILE        正文（HTML）文件，必填
  --document FILE       要作为文件发送的构建产物（可选）
  --caption FILE        文件说明（HTML，可选）
  --limit N             正文长度上限，默认 3900
  --truncate-mark TEXT  截断后追加的说明文字
  --silent              静音发送
USAGE
}

notice() { printf '::notice::%s\n' "$*"; }
warn()   { printf '::warning::%s\n' "$*"; }
fail()   { printf '::error::%s\n' "$*" >&2; }

is_true() {
  case "${1:-}" in
    1|true|TRUE|True|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

read_text() {
  local file="$1"
  if [ -f "$file" ]; then
    cat "$file"
  fi
}

# 按整行边界截断到 limit 个字符以内，超出时追加截断标记
truncate_lines() {
  local text="$1" limit="$2"
  if [ "${#text}" -le "$limit" ]; then
    printf '%s' "$text"
    return 0
  fi
  local cut="${text:0:$limit}"
  cut="${cut%$'\n'*}"
  printf '%s%s' "$cut" "$TRUNCATE_MARK"
}

retry_after_seconds() {
  local body="$1" value=""
  if command -v jq >/dev/null 2>&1; then
    value="$(printf '%s' "$body" | jq -r '.parameters.retry_after // empty' 2>/dev/null || true)"
  fi
  if [ -z "$value" ]; then
    value="$(printf '%s' "$body" | grep -o '"retry_after":[0-9]*' | head -n 1 | cut -d: -f2 || true)"
  fi
  case "$value" in
    ''|*[!0-9]*) return 0 ;;
  esac
  if [ "$value" -gt 60 ]; then
    value=60
  fi
  printf '%s' "$value"
}

# send METHOD <curl args...>：POST 到 Bot API，失败按 retry_after 退避重试
send() {
  local method="$1"; shift
  local attempt=1 response status wait_seconds
  while :; do
    status=0
    response="$(curl --fail-with-body -sS -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/${method}" "$@" 2>&1)" || status=$?
    if [ "$status" -eq 0 ]; then
      printf '%s\n' "$response"
      return 0
    fi
    if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
      fail "Telegram ${method} 调用失败（已尝试 ${MAX_ATTEMPTS} 次）: ${response}"
      return 1
    fi
    wait_seconds="$(retry_after_seconds "$response")"
    [ -n "$wait_seconds" ] || wait_seconds=5
    warn "Telegram ${method} 第 ${attempt} 次失败，${wait_seconds}s 后重试: ${response}"
    sleep "$wait_seconds"
    attempt=$((attempt + 1))
  done
}

message_id_of() {
  printf '%s' "$1" | grep -o '"message_id":[0-9]*' | head -n 1 | cut -d: -f2 || true
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --message)       MESSAGE_FILE="${2:-}"; shift 2 ;;
    --document)      DOCUMENT_FILE="${2:-}"; shift 2 ;;
    --caption)       CAPTION_FILE="${2:-}"; shift 2 ;;
    --limit)         LIMIT="${2:-}"; shift 2 ;;
    --truncate-mark) TRUNCATE_MARK="${2:-}"; shift 2 ;;
    --silent)        SILENT=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *)               usage >&2; fail "未知参数: $1"; exit 2 ;;
  esac
done

if [ -z "$MESSAGE_FILE" ]; then
  usage >&2
  fail "--message 必填"
  exit 2
fi
if [ ! -f "$MESSAGE_FILE" ]; then
  fail "找不到正文文件: $MESSAGE_FILE"
  exit 2
fi
case "$LIMIT" in
  ''|*[!0-9]*) fail "--limit 必须是正整数，实际收到: $LIMIT"; exit 2 ;;
esac

TOKEN="${TELEGRAM_BOT_TOKEN:-}"
CHAT_ID="${TELEGRAM_CHAT_ID:-}"
THREAD_ID="${TELEGRAM_MESSAGE_THREAD_ID:-}"

if [ -z "$TOKEN" ] || [ -z "$CHAT_ID" ]; then
  notice "未配置 TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID，跳过 Telegram 通知"
  exit 0
fi

printf '::add-mask::%s\n' "$TOKEN"
if is_true "${TELEGRAM_SILENT:-}"; then
  SILENT=1
fi

RAW_TEXT="$(read_text "$MESSAGE_FILE")"
if [ -z "$RAW_TEXT" ]; then
  fail "正文文件为空: $MESSAGE_FILE"
  exit 2
fi
TEXT="$(truncate_lines "$RAW_TEXT" "$LIMIT")"
if [ "${#RAW_TEXT}" -gt "$LIMIT" ]; then
  warn "正文 ${#RAW_TEXT} 字符超过 ${LIMIT} 上限，已按行截断"
fi

CAPTION=""
if [ -n "$DOCUMENT_FILE" ]; then
  CAPTION="$(basename "$DOCUMENT_FILE")"
fi
if [ -n "$CAPTION_FILE" ] && [ -f "$CAPTION_FILE" ]; then
  CAPTION="$(truncate_lines "$(read_text "$CAPTION_FILE")" 1000)"
fi

SEND_DOCUMENT=0
size_bytes=0
if [ -n "$DOCUMENT_FILE" ]; then
  if [ ! -f "$DOCUMENT_FILE" ]; then
    warn "找不到附件 ${DOCUMENT_FILE}，只发送正文"
  else
    size_bytes="$(wc -c < "$DOCUMENT_FILE" | tr -d '[:space:]')"
    if [ "$size_bytes" -gt "$DOCUMENT_MAX_BYTES" ]; then
      warn "附件 $(basename "$DOCUMENT_FILE") 约 $((size_bytes / 1048576)) MB，超过 Telegram 上传上限（50 MB），只发送正文"
    else
      SEND_DOCUMENT=1
    fi
  fi
fi

if is_true "${TELEGRAM_DRY_RUN:-}"; then
  notice "DRY RUN：不发送任何请求"
  printf '目标: %s%s\n' "$CHAT_ID" "${THREAD_ID:+（话题 ${THREAD_ID}）}"
  printf 'Token: %s****（已打码）\n' "${TOKEN:0:4}"
  printf '正文: %s 字符（上限 %s）\n' "${#TEXT}" "$LIMIT"
  printf -- '--- 正文开始 ---\n%s\n--- 正文结束 ---\n' "$TEXT"
  if [ "$SEND_DOCUMENT" -eq 1 ]; then
    printf '附件: %s（%s 字节）\n' "$DOCUMENT_FILE" "$size_bytes"
    printf -- '--- 文件说明开始 ---\n%s\n--- 文件说明结束 ---\n' "$CAPTION"
  fi
  exit 0
fi

message_args=(
  --data-urlencode "chat_id=${CHAT_ID}"
  --data-urlencode "text=${TEXT}"
  --data-urlencode "parse_mode=HTML"
  --data-urlencode 'link_preview_options={"is_disabled":true}'
)
if [ -n "$THREAD_ID" ]; then
  message_args+=( --data-urlencode "message_thread_id=${THREAD_ID}" )
fi
if [ "$SILENT" -eq 1 ]; then
  message_args+=( --data-urlencode "disable_notification=true" )
fi

response="$(send sendMessage "${message_args[@]}")" || exit 1
notice "已发送正文（message_id=$(message_id_of "$response")）"

if [ "$SEND_DOCUMENT" -eq 1 ]; then
  # --form-string：caption 以 <b> 开头时不会被 curl 当成「从文件读取」
  document_args=(
    --form-string "chat_id=${CHAT_ID}"
    --form-string "caption=${CAPTION}"
    --form-string "parse_mode=HTML"
    -F "document=@${DOCUMENT_FILE}"
  )
  if [ -n "$THREAD_ID" ]; then
    document_args+=( --form-string "message_thread_id=${THREAD_ID}" )
  fi
  if [ "$SILENT" -eq 1 ]; then
    document_args+=( --form-string "disable_notification=true" )
  fi
  response="$(send sendDocument "${document_args[@]}")" || exit 1
  notice "已发送附件 $(basename "$DOCUMENT_FILE")（message_id=$(message_id_of "$response")）"
fi
