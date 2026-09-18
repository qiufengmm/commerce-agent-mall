#!/usr/bin/env bash
# =============================================================================
# Mall 本地 Docker Compose 启动前环境校验脚本（Bash / Git Bash / WSL）
#
# 用法：
#   bash document/docker/check-env.sh
#   bash document/docker/check-env.sh .env
#
# 退出码：
#   0  校验通过
#   1  存在未通过的校验项（占位凭据 / 缺失 / 长度不足 / 重复定义）
#   2  找不到 .env 文件
#
# 安全约定：
#   本脚本只输出变量名与失败原因，绝不输出任何变量值，日志可安全粘贴到聊天或报告。
# =============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-$REPO_ROOT/.env}"

echo "=== Mall 本地环境校验（check-env.sh）==="
echo "env 文件: $ENV_FILE"
echo "安全约定：只输出变量名与失败原因，不输出任何变量值。"
echo ""

if [ ! -f "$ENV_FILE" ]; then
    echo "  [FAIL] 找不到 .env 文件。请先执行： cp .env.example .env"
    echo ""
    echo "校验未通过：缺少 .env 文件。"
    exit 2
fi

# 输出去掉首行 UTF-8 BOM 后的 .env 内容。
# Windows 记事本保存的 .env 常带 BOM，不去掉会拼在第一个变量名前，导致误判缺失。
env_content() {
    sed '1s/^\xEF\xBB\xBF//' "$ENV_FILE" 2>/dev/null
}

# 统计变量在 .env 中出现的次数（用于检测重复定义）
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
        *change-me*|*changeme*|*change_me*|*please-change*|*example*|*placeholder*|*your-*|*todo*|*fixme*)
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

check_var() {
    local name="$1"
    local min_len="$2"
    local value
    local count

    # Compose 对重复变量使用「最后一个值」，而本脚本取第一个值，
    # 两者可能不一致，因此对必检变量出现重复定义时直接判定为失败。
    count="$(count_occurrences "$name")"
    if [ "$count" -gt 1 ]; then
        echo "  [FAIL] ${name}: .env 中存在重复定义（Compose 取最后一个值，可能与首个值不一致）"
        FAILURES=$((FAILURES + 1))
        return
    fi

    value="$(get_value "$name")"

    if [ -z "$value" ]; then
        echo "  [FAIL] ${name}: .env 中缺少该变量或值为空"
        FAILURES=$((FAILURES + 1))
        return
    fi

    if is_placeholder "$value"; then
        echo "  [FAIL] ${name}: 仍是占位值，请替换为自己的本地凭据"
        FAILURES=$((FAILURES + 1))
        return
    fi

    if [ "$min_len" -gt 0 ] && [ "${#value}" -lt "$min_len" ]; then
        echo "  [FAIL] ${name}: 长度不足（要求 >= ${min_len} 个字符）"
        FAILURES=$((FAILURES + 1))
        return
    fi

    echo "  [ OK ] ${name}: 已设置且非占位值"
}

echo "--- 敏感变量校验 ---"
check_var "MYSQL_ROOT_PASSWORD"        0
check_var "RABBITMQ_PASSWORD"          0
check_var "MINIO_ROOT_USER"            3
check_var "MINIO_ROOT_PASSWORD"        8
check_var "MALL_SEARCH_INTERNAL_TOKEN" 16

echo ""
echo "--- 网络暴露检查 ---"

EXPOSED=0
for bind_var in MINIO_BIND_ADDR NGINX_BIND_ADDR; do
    bind_value="$(get_value "$bind_var")"
    if [ "$bind_value" = "0.0.0.0" ]; then
        echo "  [WARN] ${bind_var}=0.0.0.0：该服务将暴露到局域网，请确认所处网络可信。"
        WARNINGS=$((WARNINGS + 1))
        EXPOSED=1
    fi
done

if [ "$EXPOSED" -eq 0 ]; then
    echo "  [ OK ] MINIO_BIND_ADDR / NGINX_BIND_ADDR 未设置为 0.0.0.0"
fi

echo "  [INFO] Redis、MongoDB、Elasticsearch 本地配置无认证，Compose 已硬编码绑定 127.0.0.1，"
echo "         且未提供任何把它们整体暴露到 0.0.0.0 的开关，请勿手工修改端口映射。"

echo ""
if [ "$FAILURES" -gt 0 ]; then
    echo "校验未通过：${FAILURES} 项。"
    echo "请修改 .env 中对应变量后重试；不要把填写后的 .env 提交到仓库。"
    echo "注意：docker compose config --quiet 只校验 YAML 与变量渲染，不能代替本脚本。"
    exit 1
fi

echo "校验通过：5 个敏感变量均已替换；警告 ${WARNINGS} 项。"
echo "下一步可执行： docker compose --env-file .env config --quiet"
exit 0
