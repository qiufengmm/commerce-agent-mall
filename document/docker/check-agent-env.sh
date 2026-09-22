#!/usr/bin/env bash
# =============================================================================
# Mall 商品导购智能体（mall-shopping-agent）启动前环境校验脚本（Bash / Git Bash / WSL）
#
# 用法：
#   bash document/docker/check-agent-env.sh
#   bash document/docker/check-agent-env.sh .env.example
#
# 校验内容：
#   1. MALL_AGENT_MODEL_MODE 只能是 openai 或 stub；
#   2. openai 模式要求 MALL_AGENT_OPENAI_BASE_URL、MALL_AGENT_OPENAI_MODEL、
#      MALL_AGENT_OPENAI_API_KEY 均为非占位值；stub 模式允许 API Key 为空；
#   3. MALL_AGENT_OPENAI_BASE_URL 必须是 http(s) 地址并包含 /v1；
#   4. MALL_AGENT_PORTAL_BASE_URL、MALL_AGENT_REDIS_URL 协议合法；
#   5. AGENT_PORT 为 1..65535 的端口号；MALL_AGENT_LOG_LEVEL 取值合法；
#   6. 必检变量出现重复定义时直接判定为失败（拒绝只取最后一个值）。
#
# 退出码：
#   0  校验通过
#   1  存在未通过的校验项（占位值 / 缺失 / 非法地址 / 非法端口 / 非法模式 / 重复定义）
#   2  找不到 env 文件
#
# 安全约定：
#   本脚本只输出变量名、固定提示与失败原因，绝不输出任何变量值，
#   日志可安全粘贴到聊天或报告。
# =============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-$REPO_ROOT/.env}"

echo "=== 商品导购智能体环境校验（check-agent-env.sh）==="
echo "env 文件: $ENV_FILE"
echo "安全约定：只输出变量名与失败原因，不输出任何变量值。"
echo ""

if [ ! -f "$ENV_FILE" ]; then
    echo "  [FAIL] 找不到 env 文件。请先执行： cp .env.example .env"
    echo ""
    echo "校验未通过：缺少 env 文件。"
    exit 2
fi

# 输出去掉首行 UTF-8 BOM 后的内容。
# Windows 记事本保存的 .env 常带 BOM，不去掉会拼在第一个变量名前，导致误判缺失。
env_content() {
    sed '1s/^\xEF\xBB\xBF//' "$ENV_FILE" 2>/dev/null
}

# 统计变量在 env 文件中出现的次数（用于检测重复定义）
count_occurrences() {
    local key="$1"
    local n
    n="$(env_content | grep -c -E "^[[:space:]]*${key}[[:space:]]*=" || true)"
    [ -z "$n" ] && n=0
    printf '%s' "$n"
}

# 读取指定变量的值（只取第一个匹配行，去掉行首尾空白与成对引号）
get_value() {
    local key="$1"
    local line
    line="$(env_content | grep -m1 -E "^[[:space:]]*${key}[[:space:]]*=" || true)"
    [ -z "$line" ] && { printf ''; return; }

    line="${line#*=}"
    line="$(printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

    case "$line" in
        \"*\") line="${line#\"}"; line="${line%\"}" ;;
        \'*\') line="${line#\'}"; line="${line%\'}" ;;
    esac
    printf '%s' "$line"
}

# 读取变量值；缺失或为空时返回默认值（默认值只用于地址与端口这类有安全缺省的项）
value_or_default() {
    local key="$1"
    local default_value="$2"
    local v
    v="$(get_value "$key")"
    if [ -z "$v" ]; then
        printf '%s' "$default_value"
    else
        printf '%s' "$v"
    fi
}

# 占位值判定：命中即视为「复制后未修改」
is_placeholder() {
    local v="${1-}"
    [ -z "$v" ] && return 0

    case "$v" in
        *'<'*|*'>'*) return 0 ;;
    esac

    local lower
    lower="$(printf '%s' "$v" | tr '[:upper:]' '[:lower:]')"
    case "$lower" in
        *change-me*|*changeme*|*change_me*|*please-change*|*pleasechange*|*example*|*placeholder*|*your-*|*todo*|*fixme*)
            return 0
            ;;
    esac

    case "$v" in
        *请填写*|*请设置*|*请生成*|*请替换*) return 0 ;;
    esac

    return 1
}

FAILURES=0
WARNINGS=0

# 重复定义检测：命中时计一次失败，并让调用方跳过后续取值校验。
# 不使用「覆盖后继续」的方式，避免 .env 首值是合规密码、末值是占位符时被误判为通过。
check_duplicate() {
    local name="$1"
    local count
    count="$(count_occurrences "$name")"
    if [ "$count" -gt 1 ]; then
        echo "  [FAIL] ${name}: env 文件中存在重复定义（拒绝只取最后一个值）"
        FAILURES=$((FAILURES + 1))
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# 1. 模型模式
# ---------------------------------------------------------------------------
echo "--- 模型模式 ---"
MODEL_MODE="openai"
if check_duplicate "MALL_AGENT_MODEL_MODE"; then
    raw_mode="$(value_or_default "MALL_AGENT_MODEL_MODE" "openai")"
    raw_mode="$(printf '%s' "$raw_mode" | tr '[:upper:]' '[:lower:]')"
    case "$raw_mode" in
        openai|stub)
            MODEL_MODE="$raw_mode"
            ;;
        *)
            echo "  [FAIL] MALL_AGENT_MODEL_MODE: 只能是 openai 或 stub"
            FAILURES=$((FAILURES + 1))
            ;;
    esac
fi

if [ "$MODEL_MODE" = "stub" ]; then
    echo "  [ OK ] MALL_AGENT_MODEL_MODE: 已设置为 stub（离线演示，允许没有模型 Key）"
    WARNINGS=$((WARNINGS + 1))
else
    echo "  [ OK ] MALL_AGENT_MODEL_MODE: 已设置为 openai"
fi

# ---------------------------------------------------------------------------
# 2. 模型服务地址与模型名
# ---------------------------------------------------------------------------
echo ""
echo "--- 模型服务配置 ---"

if check_duplicate "MALL_AGENT_OPENAI_BASE_URL"; then
    BASE_URL="$(get_value "MALL_AGENT_OPENAI_BASE_URL")"
    if is_placeholder "$BASE_URL"; then
        echo "  [FAIL] MALL_AGENT_OPENAI_BASE_URL: 缺失或仍是占位值"
        FAILURES=$((FAILURES + 1))
    else
        case "$BASE_URL" in
            http://*|https://*)
                base_without_slash="${BASE_URL%/}"
                case "$base_without_slash" in
                    */v1)
                        echo "  [ OK ] MALL_AGENT_OPENAI_BASE_URL: 已设置为 http(s) 且包含 /v1"
                        ;;
                    *)
                        echo "  [FAIL] MALL_AGENT_OPENAI_BASE_URL: 必须以 /v1 结尾（服务端会据此拼接 /chat/completions）"
                        FAILURES=$((FAILURES + 1))
                        ;;
                esac
                ;;
            *)
                echo "  [FAIL] MALL_AGENT_OPENAI_BASE_URL: 必须是 http(s) 地址"
                FAILURES=$((FAILURES + 1))
                ;;
        esac
    fi
fi

if check_duplicate "MALL_AGENT_OPENAI_MODEL"; then
    MODEL_NAME="$(get_value "MALL_AGENT_OPENAI_MODEL")"
    if is_placeholder "$MODEL_NAME"; then
        echo "  [FAIL] MALL_AGENT_OPENAI_MODEL: 缺失或仍是占位值"
        FAILURES=$((FAILURES + 1))
    else
        echo "  [ OK ] MALL_AGENT_OPENAI_MODEL: 已设置且非占位值"
    fi
fi

# ---------------------------------------------------------------------------
# 3. 模型 Key（openai 模式必填，stub 模式可空）
# ---------------------------------------------------------------------------
echo ""
echo "--- 模型凭据 ---"

if check_duplicate "MALL_AGENT_OPENAI_API_KEY"; then
    API_KEY="$(get_value "MALL_AGENT_OPENAI_API_KEY")"
    if [ "$MODEL_MODE" = "stub" ]; then
        if [ -z "$API_KEY" ]; then
            echo "  [ OK ] MALL_AGENT_OPENAI_API_KEY: stub 模式下允许为空"
        else
            echo "  [ OK ] MALL_AGENT_OPENAI_API_KEY: 已设置（stub 模式不会使用）"
        fi
    elif [ -z "$API_KEY" ]; then
        echo "  [FAIL] MALL_AGENT_OPENAI_API_KEY: openai 模式必须提供真实 Key"
        FAILURES=$((FAILURES + 1))
    elif is_placeholder "$API_KEY"; then
        echo "  [FAIL] MALL_AGENT_OPENAI_API_KEY: 仍是占位值，openai 模式会拒绝启动聊天能力"
        FAILURES=$((FAILURES + 1))
    else
        echo "  [ OK ] MALL_AGENT_OPENAI_API_KEY: 已设置且非占位值"
    fi
fi

# ---------------------------------------------------------------------------
# 4. 只读依赖地址
# ---------------------------------------------------------------------------
echo ""
echo "--- 只读依赖地址 ---"

if check_duplicate "MALL_AGENT_PORTAL_BASE_URL"; then
    PORTAL_URL="$(value_or_default "MALL_AGENT_PORTAL_BASE_URL" "http://localhost:8085")"
    case "$PORTAL_URL" in
        http://*|https://*)
            echo "  [ OK ] MALL_AGENT_PORTAL_BASE_URL: 已设置为 http(s) 地址"
            ;;
        *)
            echo "  [FAIL] MALL_AGENT_PORTAL_BASE_URL: 必须是 http(s) 地址"
            FAILURES=$((FAILURES + 1))
            ;;
    esac
fi

if check_duplicate "MALL_AGENT_REDIS_URL"; then
    REDIS_URL="$(value_or_default "MALL_AGENT_REDIS_URL" "redis://localhost:6379/0")"
    case "$REDIS_URL" in
        redis://*|rediss://*)
            echo "  [ OK ] MALL_AGENT_REDIS_URL: 已设置为 redis 地址"
            ;;
        *)
            echo "  [FAIL] MALL_AGENT_REDIS_URL: 必须是 redis:// 或 rediss:// 地址"
            FAILURES=$((FAILURES + 1))
            ;;
    esac
fi

# ---------------------------------------------------------------------------
# 5. 端口与日志级别
# ---------------------------------------------------------------------------
echo ""
echo "--- 端口与日志 ---"

if check_duplicate "AGENT_PORT"; then
    AGENT_PORT_VALUE="$(value_or_default "AGENT_PORT" "8086")"
    case "$AGENT_PORT_VALUE" in
        ''|*[!0-9]*)
            echo "  [FAIL] AGENT_PORT: 必须是 1..65535 的端口号"
            FAILURES=$((FAILURES + 1))
            ;;
        *)
            if [ "$AGENT_PORT_VALUE" -ge 1 ] && [ "$AGENT_PORT_VALUE" -le 65535 ]; then
                echo "  [ OK ] AGENT_PORT: 已设置为合法端口"
            else
                echo "  [FAIL] AGENT_PORT: 必须是 1..65535 的端口号"
                FAILURES=$((FAILURES + 1))
            fi
            ;;
    esac
fi

if check_duplicate "MALL_AGENT_LOG_LEVEL"; then
    LOG_LEVEL="$(value_or_default "MALL_AGENT_LOG_LEVEL" "INFO")"
    LOG_LEVEL="$(printf '%s' "$LOG_LEVEL" | tr '[:lower:]' '[:upper:]')"
    case "$LOG_LEVEL" in
        CRITICAL|ERROR|WARNING|INFO|DEBUG)
            echo "  [ OK ] MALL_AGENT_LOG_LEVEL: 取值合法"
            ;;
        *)
            echo "  [FAIL] MALL_AGENT_LOG_LEVEL: 取值不在允许范围内"
            FAILURES=$((FAILURES + 1))
            ;;
    esac
fi

# ---------------------------------------------------------------------------
# 6. 安全提示
# ---------------------------------------------------------------------------
echo ""
echo "--- 安全提示 ---"
echo "  [INFO] MALL_AGENT_OPENAI_API_KEY 只应存在于本机 .env 或进程环境变量中。"
echo "  [INFO] 服务日志不会记录 Authorization、API Key、完整提示词或完整模型响应。"
echo "  [INFO] 本服务只读访问 mall-portal，不直连 MySQL / MongoDB / RabbitMQ / Elasticsearch。"

# ---------------------------------------------------------------------------
# 结果
# ---------------------------------------------------------------------------
echo ""
if [ "$FAILURES" -gt 0 ]; then
    echo "校验未通过：${FAILURES} 项。"
    echo "请修改对应变量后重试；不要把填写后的 .env 提交到仓库。"
    exit 1
fi

echo "校验通过；警告 ${WARNINGS} 项。"
echo "下一步可执行： docker compose --env-file .env --profile app config --quiet"
exit 0
