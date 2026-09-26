#!/usr/bin/env bash
#
# BetterHeybox Telegram 通知发送器（CI / Release / Telegram test 共用）
#
# 每次调用只发一条消息：
#   有 --document → sendDocument，正文作为文件说明（caption）
#   无 --document → sendMessage，正文作为普通消息
#
# 用法:
#   telegram-notify.sh --message FILE [--document FILE] [--limit N]
#                      [--truncate-mark TEXT] [--silent]
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
#   - 长度按「去掉 HTML 标签后的可见字符」计算（Telegram 的 entities parsing 语义）
#   - 超长时从末尾按整行丢弃（不会截断标签），再追加截断标记；
#     单行本身就超长时才按字符硬截断
#   - 默认上限：文件说明 1000（Bot API 上限 1024）/ 普通消息 3900（上限 4096）
#   - 429 / 网络错误最多重试 3 次，遵循响应里的 retry_after（上限 60s）
#   - 附件超过 49 MB（Bot 上传上限 50 MB）时改为发普通消息
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
LIMIT=""
TRUNCATE_MARK=$'\n…（内容过长已截断，完整内容见上方链接）'
SILENT=0
MAX_ATTEMPTS=3
CAPTION_LIMIT=1000
MESSAGE_LIMIT=3900
DOCUMENT_MAX_BYTES=$((49 * 1024 * 1024))

usage() {
  cat <<'USAGE'
用法: telegram-notify.sh --message FILE [--document FILE] [--limit N]
                         [--truncate-mark TEXT] [--silent]
  --message FILE        正文（HTML）文件，必填；有附件时作为文件说明
  --document FILE       随消息一起发送的构建产物（可选）
  --limit N             可见字符上限，默认：有附件 1000 / 无附件 3900
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

# Telegram 的 4096 / 1024 都按 entities parsing 之后的可见字符计，标签与 href 不计入
parsed_length() {
  printf '%s' "$1" | sed -e 's/<[^>]*>//g' | wc -m | tr -d '[:space:]'
}

# 超长时按整行丢弃末尾内容，直到可见字符数放得下（含截断标记）
fit_message() {
  local text="$1" limit="$2" budget marker_len attempts=0
  marker_len="$(parsed_length "$TRUNCATE_MARK")"
  budget=$((limit - marker_len))
  [ "$budget" -lt 1 ] && budget=1
  while [ "$(parsed_length "$text")" -gt "$budget" ]; do
    if [ -z "$text" ]; then
      break
    fi
    if [ "$attempts" -ge 500 ]; then
      text="${text:0:$budget}"
      break
    fi
    text="${text%$'\n'*}"
    attempts=$((attempts + 1))
  done
  if [ "$attempts" -gt 0 ]; then
    printf '%s%s' "$text" "$TRUNCATE_MARK"
  else
    printf '%s' "$text"
  fi
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
if [ -n "$LIMIT" ]; then
  case "$LIMIT" in
    ''|*[!0-9]*) fail "--limit 必须是正整数，实际收到: $LIMIT"; exit 2 ;;
  esac
fi

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

# 先定附件能不能发，再定正文上限（附件在 → 正文是 caption，上限更小）
SEND_DOCUMENT=0
size_bytes=0
if [ -n "$DOCUMENT_FILE" ]; then
  if [ ! -f "$DOCUMENT_FILE" ]; then
    warn "找不到附件 ${DOCUMENT_FILE}，改为发送普通消息"
  else
    size_bytes="$(wc -c < "$DOCUMENT_FILE" | tr -d '[:space:]')"
    if [ "$size_bytes" -gt "$DOCUMENT_MAX_BYTES" ]; then
      warn "附件 $(basename "$DOCUMENT_FILE") 约 $((size_bytes / 1048576)) MB，超过 Telegram 上传上限（50 MB），改为发送普通消息"
    else
      SEND_DOCUMENT=1
    fi
  fi
fi

if [ -z "$LIMIT" ]; then
  if [ "$SEND_DOCUMENT" -eq 1 ]; then
    LIMIT="$CAPTION_LIMIT"
  else
    LIMIT="$MESSAGE_LIMIT"
  fi
fi

RAW_TEXT="$(read_text "$MESSAGE_FILE")"
if [ -z "$RAW_TEXT" ]; then
  fail "正文文件为空: $MESSAGE_FILE"
  exit 2
fi
TEXT="$(fit_message "$RAW_TEXT" "$LIMIT")"
raw_visible="$(parsed_length "$RAW_TEXT")"
visible="$(parsed_length "$TEXT")"
if [ "$raw_visible" -gt "$LIMIT" ]; then
  warn "正文可见字符 ${raw_visible} 超过上限 ${LIMIT}，已按行截断到 ${visible}"
fi

if is_true "${TELEGRAM_DRY_RUN:-}"; then
  if [ "$SEND_DOCUMENT" -eq 1 ]; then
    notice "DRY RUN：将发送 1 条文件说明（caption，上限 ${LIMIT}）"
  else
    notice "DRY RUN：将发送 1 条普通消息（上限 ${LIMIT}）"
  fi
  printf '目标: %s%s\n' "$CHAT_ID" "${THREAD_ID:+（话题 ${THREAD_ID}）}"
  printf 'Token: %s****（已打码）\n' "${TOKEN:0:4}"
  printf '可见字符: %s / %s\n' "$visible" "$LIMIT"
  printf -- '--- 正文开始 ---\n%s\n--- 正文结束 ---\n' "$TEXT"
  if [ "$SEND_DOCUMENT" -eq 1 ]; then
    printf '附件: %s（%s 字节）\n' "$DOCUMENT_FILE" "$size_bytes"
  fi
  exit 0
fi

common_args=()
if [ -n "$THREAD_ID" ]; then
  common_args+=( --form-string "message_thread_id=${THREAD_ID}" )
fi
if [ "$SILENT" -eq 1 ]; then
  common_args+=( --form-string "disable_notification=true" )
fi

if [ "$SEND_DOCUMENT" -eq 1 ]; then
  # --form-string：caption 以 <b> 开头时不会被 curl 当成「从文件读取」
  document_args=(
    --form-string "chat_id=${CHAT_ID}"
    --form-string "caption=${TEXT}"
    --form-string "parse_mode=HTML"
    -F "document=@${DOCUMENT_FILE}"
  )
  response="$(send sendDocument "${document_args[@]}" "${common_args[@]}")" || exit 1
  notice "已发送附件 $(basename "$DOCUMENT_FILE")（message_id=$(message_id_of "$response")）"
else
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
  notice "已发送消息（message_id=$(message_id_of "$response")）"
fi
