"""Compose / Nginx / Dockerfile 静态结构断言。

这些用例不启动容器、不访问网络；动态渲染校验由 ``docker compose config``
命令在验收步骤中单独执行。其中环境校验脚本部分会以子进程方式实际运行脚本，
用临时 env 文件验证退出码与输出脱敏。
"""

from __future__ import annotations

import ipaddress
import os
import re
import shutil
import subprocess
from functools import lru_cache
from pathlib import Path

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[4]
COMPOSE_PATH = REPO_ROOT / "docker-compose.yml"
NGINX_CONF_PATH = REPO_ROOT / "document" / "docker" / "nginx" / "conf.d" / "default.conf"
AGENT_DOCKERFILE_PATH = REPO_ROOT / "document" / "docker" / "Dockerfile.agent"
AGENT_DOCKERIGNORE_PATH = REPO_ROOT / "mall-shopping-agent" / ".dockerignore"
# Task 12：Java 运行实现的构建模板、根构建上下文忽略规则与配置来源
APP_DOCKERFILE_PATH = REPO_ROOT / "document" / "docker" / "Dockerfile.app"
ROOT_DOCKERIGNORE_PATH = REPO_ROOT / ".dockerignore"
AGENT_PROPERTIES_PATH = (
    REPO_ROOT
    / "mall-master"
    / "mall-agent"
    / "src"
    / "main"
    / "java"
    / "com"
    / "macro"
    / "mall"
    / "agent"
    / "config"
    / "AgentProperties.java"
)
ENV_EXAMPLE_PATH = REPO_ROOT / ".env.example"
CHECK_SCRIPT_PATH = REPO_ROOT / "document" / "docker" / "check-agent-env.ps1"
BASH_SCRIPT_PATH = REPO_ROOT / "document" / "docker" / "check-agent-env.sh"
PLAN_PATH = (
    REPO_ROOT / "docs" / "superpowers" / "plans" / "2026-09-22-python-product-shopping-agent.md"
)

SERVICE_NAME = "mall-shopping-agent"

# 计划与脚本中都不允许出现的不存在的参数
UNSUPPORTED_PARAMETER = "-AllowPlaceholderForConfigCheck"

# 必检变量的重复定义必须让校验失败（不得只取最后一个值）
DUPLICATE_CHECKED_VARIABLES = (
    "MALL_AGENT_MODEL_MODE",
    "MALL_AGENT_OPENAI_BASE_URL",
    "MALL_AGENT_OPENAI_MODEL",
    "MALL_AGENT_OPENAI_API_KEY",
    "AGENT_PORT",
)

# 输出语句中允许出现的变量：只允许变量名、计数器与 env 文件路径
ALLOWED_OUTPUT_VARIABLES = {
    "EnvFile",
    "ENV_FILE",
    "Name",
    "name",
    "failures",
    "warnings",
    "FAILURES",
    "WARNINGS",
}

SCRIPT_NAMES = ("powershell", "bash")

# 只用于脚本行为的本地占位值，不是真实凭据，也不会写入报告
TEST_BASE_URL = "https://model.local.test/v1"
TEST_MODEL_NAME = "local-test-model"
TEST_API_KEY = "sk-local-test-only-value"

FORBIDDEN_AGENT_ENV_KEYS = {
    "MYSQL_ROOT_PASSWORD",
    "RABBITMQ_PASSWORD",
    "MINIO_ROOT_PASSWORD",
    "MINIO_ROOT_USER",
    "SPRING_DATASOURCE_PASSWORD",
    "SPRING_DATASOURCE_USERNAME",
    "MALL_SEARCH_INTERNAL_TOKEN",
    "SPRING_DATASOURCE_URL",
    "SPRING_RABBITMQ_HOST",
    "SPRING_DATA_MONGODB_HOST",
    "SPRING_ELASTICSEARCH_URIS",
}

# AgentProperties.ENVIRONMENT_VARIABLES 声明的全部 23 个 MALL_AGENT_* 变量。
# 该列表以源码为准（见 _source_mall_agent_variables），此处冗余一份便于断言可读性。
MALL_AGENT_ENVIRONMENT_VARIABLES = (
    "MALL_AGENT_HOST",
    "MALL_AGENT_PORT",
    "MALL_AGENT_LOG_LEVEL",
    "MALL_AGENT_CORS_ALLOW_ORIGINS",
    "MALL_AGENT_TRUSTED_PROXY_IP",
    "MALL_AGENT_REQUEST_TIMEOUT_SECONDS",
    "MALL_AGENT_MODEL_MODE",
    "MALL_AGENT_OPENAI_BASE_URL",
    "MALL_AGENT_OPENAI_API_KEY",
    "MALL_AGENT_OPENAI_MODEL",
    "MALL_AGENT_OPENAI_TIMEOUT_SECONDS",
    "MALL_AGENT_MAX_TOOL_ROUNDS",
    "MALL_AGENT_PORTAL_BASE_URL",
    "MALL_AGENT_PORTAL_TIMEOUT_SECONDS",
    "MALL_AGENT_REDIS_URL",
    "MALL_AGENT_SESSION_TTL_SECONDS",
    "MALL_AGENT_SESSION_MAX_MESSAGES",
    "MALL_AGENT_RATE_LIMIT_SESSION_LIMIT",
    "MALL_AGENT_RATE_LIMIT_SESSION_WINDOW_SECONDS",
    "MALL_AGENT_RATE_LIMIT_IP_LIMIT",
    "MALL_AGENT_RATE_LIMIT_IP_WINDOW_SECONDS",
    "MALL_AGENT_TOOL_RESULT_MAX_CHARS",
    "MALL_AGENT_CONTEXT_MAX_CHARS",
)

# agent 容器除白名单外唯一允许的额外变量（时区，来自基础设施约定）
ALLOWED_NON_WHITELIST_AGENT_ENV_KEYS = {"TZ"}

# --------------------------------------------------------------------------- #
# 配置契约：23 个 MALL_AGENT_* 变量分为「Compose 可调」与「Compose 容器固定值」两类。
# 该契约同时约束 docker-compose.yml 与 .env.example，避免 .env.example 把
# Compose 写死的参数误标成宿主 .env 可调。
# --------------------------------------------------------------------------- #

# A 类（Compose 以 ${VAR:-默认值} 插值，宿主 .env 可覆盖）：变量名 -> 默认值
AGENT_COMPOSE_INTERPOLATED_VARIABLES = {
    "MALL_AGENT_LOG_LEVEL": "INFO",
    # CORS 默认留空表示「不授权任何跨域来源」（fail-closed）：
    # 同源 H5 经 Nginx /agent-api/ 访问不需要 CORS；跨域直连必须显式填写精确来源。
    # 不得使用 ``:-*`` 之类的通配兜底。
    "MALL_AGENT_CORS_ALLOW_ORIGINS": "",
    "MALL_AGENT_MODEL_MODE": "openai",
    "MALL_AGENT_OPENAI_BASE_URL": "https://api.openai.com/v1",
    "MALL_AGENT_OPENAI_MODEL": "gpt-4o-mini",
    "MALL_AGENT_OPENAI_API_KEY": "",
    "MALL_AGENT_SESSION_TTL_SECONDS": "86400",
}

# B 类（Compose 直接写死字面量，不读取宿主 .env 覆盖）：变量名 -> 固定值
AGENT_COMPOSE_FIXED_VARIABLES = {
    "MALL_AGENT_HOST": "0.0.0.0",
    "MALL_AGENT_PORT": "8086",
    # 唯一可信反向代理地址：必须与 Nginx 在 agent-proxy-net 上的静态地址逐字一致，
    # 且为 Compose 固定项，不受宿主 .env 覆盖。
    "MALL_AGENT_TRUSTED_PROXY_IP": "172.21.240.2",
    "MALL_AGENT_REQUEST_TIMEOUT_SECONDS": "35",
    "MALL_AGENT_OPENAI_TIMEOUT_SECONDS": "30",
    "MALL_AGENT_MAX_TOOL_ROUNDS": "4",
    "MALL_AGENT_PORTAL_BASE_URL": "http://mall-portal:8085",
    "MALL_AGENT_PORTAL_TIMEOUT_SECONDS": "10",
    "MALL_AGENT_REDIS_URL": "redis://redis:6379/0",
    "MALL_AGENT_SESSION_MAX_MESSAGES": "20",
    "MALL_AGENT_RATE_LIMIT_SESSION_LIMIT": "20",
    "MALL_AGENT_RATE_LIMIT_SESSION_WINDOW_SECONDS": "300",
    "MALL_AGENT_RATE_LIMIT_IP_LIMIT": "60",
    "MALL_AGENT_RATE_LIMIT_IP_WINDOW_SECONDS": "300",
    "MALL_AGENT_TOOL_RESULT_MAX_CHARS": "4000",
    "MALL_AGENT_CONTEXT_MAX_CHARS": "16000",
}

# Nginx 对 /agent-api/ 的 proxy_read_timeout：必须覆盖「门户身份解析 10s + Agent 请求 35s」
# 的服务端总预算，且固定 35 秒的 Agent 请求超时必须严格小于它。
NGINX_AGENT_READ_TIMEOUT_SECONDS = 60

# .env.example 中用于切分两段契约的小标题（必须逐字出现在文件中）
ENV_EXAMPLE_ADJUSTABLE_HEADER = "# --- A. Compose 可调（宿主 .env 覆盖生效） ---"
ENV_EXAMPLE_FIXED_HEADER = (
    "# --- B. Compose 容器固定值（不通过宿主 .env 覆盖；仅供独立运行 / 本机 Java 参考） ---"
)

# CORS 契约：默认必须留空（fail-closed），绝不允许通配兜底。
CORS_VARIABLE = "MALL_AGENT_CORS_ALLOW_ORIGINS"
# 显式跨域来源样例（仅测试用，非真实域名），用于验证显式精确来源被原样保留
CORS_EXPLICIT_ORIGINS = "https://h5.example.test,https://admin.example.test"
# .env.example 中该变量注释必须说明「默认不授权跨域」与「跨域直连需显式精确来源」
CORS_ENV_EXAMPLE_COMMENT_MARKERS = ("默认不授权任何跨域来源", "显式填写精确来源")

# --------------------------------------------------------------------------- #
# 安全隔离契约：Agent 专用代理网络 / 后端网络、可信代理解析与运行用户。
# 该契约同时约束 docker-compose.yml、.env.example 与 Java 侧 ClientIpResolver。
# --------------------------------------------------------------------------- #
AGENT_PROXY_NETWORK = "agent-proxy-net"
AGENT_BACKEND_NETWORK = "agent-backend-net"
# 专用代理子网（经只读 docker network inspect 复核：本机未占用 172.21 段）
AGENT_PROXY_SUBNET = "172.21.240.0/29"
# 固定静态地址：Nginx 是唯一可信代理，Agent 是唯一被代理的后端
NGINX_PROXY_STATIC_IP = "172.21.240.2"
AGENT_PROXY_STATIC_IP = "172.21.240.3"
# 仅 mall-shopping-agent 以非 root 运行
AGENT_RUN_USER = "10001:10001"
# Agent 采信 X-Real-IP 的唯一可信代理地址变量
TRUSTED_PROXY_VARIABLE = "MALL_AGENT_TRUSTED_PROXY_IP"
# .env.example 的 B 段（固定值）只作「宿主机直接运行」参考，其中可信代理地址必须留空：
# 复制 .env.example 为 .env 后不经 Compose 直接跑 Java 时，默认不信任任何代理；
# Compose 容器由 docker-compose.yml 固定注入 172.21.240.2，不受宿主 .env 覆盖。
# 因此这是 B 段唯一允许与 AGENT_COMPOSE_FIXED_VARIABLES 取值不同的 host-only 例外。
ENV_EXAMPLE_HOST_ONLY_EMPTY_FIXED = {TRUSTED_PROXY_VARIABLE: ""}
# .env.example 中该例外必须逐字出现的说明（防止留空被误当成漏填）
TRUSTED_PROXY_ENV_EXAMPLE_MARKERS = (
    "直接运行默认不信任代理",
    "Compose 容器固定注入 172.21.240.2",
)


@pytest.fixture(scope="module")
def compose() -> dict:
    return yaml.safe_load(COMPOSE_PATH.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def agent_service(compose: dict) -> dict:
    services = compose.get("services", {})
    assert SERVICE_NAME in services, "docker-compose.yml 缺少 mall-shopping-agent 服务"
    return services[SERVICE_NAME]


def _dependency_edges(compose: dict) -> dict[str, set[str]]:
    edges: dict[str, set[str]] = {}
    for name, service in compose.get("services", {}).items():
        depends = service.get("depends_on") or {}
        if isinstance(depends, list):
            edges[name] = set(depends)
        else:
            edges[name] = set(depends.keys())
    return edges


def test_agent_service_is_registered_in_app_profile(agent_service: dict) -> None:
    assert agent_service.get("profiles") == ["app"]


def test_agent_service_waits_for_redis_and_portal(agent_service: dict) -> None:
    depends = agent_service.get("depends_on") or {}

    assert depends["redis"]["condition"] == "service_healthy"
    assert depends["mall-portal"]["condition"] == "service_healthy"


def test_agent_service_resolves_dependencies_without_cycles(compose: dict) -> None:
    edges = _dependency_edges(compose)
    assert SERVICE_NAME in edges

    visiting: set[str] = set()
    visited: set[str] = set()

    def walk(node: str) -> None:
        assert node not in visiting, f"检测到依赖环：{node}"
        if node in visited:
            return
        visiting.add(node)
        for dependency in edges.get(node, set()):
            if dependency in edges:
                walk(dependency)
        visiting.discard(node)
        visited.add(node)

    for service in edges:
        walk(service)


def test_agent_published_ports_bind_localhost_only(agent_service: dict) -> None:
    ports = agent_service.get("ports") or []
    assert ports, "mall-shopping-agent 必须发布端口供 Nginx 与本地联调访问"

    for entry in ports:
        assert entry.startswith("127.0.0.1:"), entry
        assert entry.endswith(":8086"), entry


def test_agent_container_never_receives_database_credentials(agent_service: dict) -> None:
    environment = agent_service.get("environment") or {}
    keys = set(environment) if isinstance(environment, dict) else set()
    if isinstance(environment, list):
        keys = {str(item).split("=", 1)[0] for item in environment}

    leaked = keys & FORBIDDEN_AGENT_ENV_KEYS
    assert leaked == set(), f"agent 容器不应拿到基础设施凭据：{sorted(leaked)}"
    assert "env_file" not in agent_service, "不要整体注入根 .env，避免把基础设施凭据带进 agent 容器"


def test_agent_container_targets_portal_and_redis_by_service_name(agent_service: dict) -> None:
    environment = agent_service["environment"]

    assert environment["MALL_AGENT_OPENAI_BASE_URL"].startswith("${MALL_AGENT_OPENAI_BASE_URL")
    assert environment["MALL_AGENT_PORTAL_BASE_URL"] == "http://mall-portal:8085"
    assert environment["MALL_AGENT_REDIS_URL"] == "redis://redis:6379/0"
    assert environment["MALL_AGENT_PORT"] == "8086"


def test_agent_healthcheck_targets_liveness_endpoint(agent_service: dict) -> None:
    healthcheck = agent_service["healthcheck"]
    command = " ".join(healthcheck["test"])

    assert "/health/live" in command
    assert "8086" in command


def test_nginx_proxies_agent_api_with_long_read_timeout() -> None:
    content = NGINX_CONF_PATH.read_text(encoding="utf-8")

    assert "location /agent-api/" in content
    assert "mall-shopping-agent:8086" in content
    assert "rewrite ^/agent-api/?(.*)$ /$1 break;" in content
    assert f"proxy_read_timeout    {NGINX_AGENT_READ_TIMEOUT_SECONDS}s;" in content


def test_nginx_proxy_keeps_authorization_header_passthrough() -> None:
    content = NGINX_CONF_PATH.read_text(encoding="utf-8")
    agent_block = content.split("location /agent-api/", 1)[1].split("location ", 1)[0]

    # 没有显式清空 Authorization，也没有把它重写成固定值
    assert "proxy_set_header Authorization" not in agent_block


def test_agent_dockerfile_is_python_only_and_has_no_recursive_delete() -> None:
    content = AGENT_DOCKERFILE_PATH.read_text(encoding="utf-8")

    assert content.startswith("#")
    assert "FROM python:3.11-slim" in content
    assert "uvicorn" in content
    assert "mall_shopping_agent.main:app" in content
    for forbidden in ("rm -rf", "del /s", "rd /s", "rmdir /s"):
        assert forbidden not in content, forbidden


def test_agent_dockerignore_excludes_local_state() -> None:
    entries = {
        line.strip()
        for line in AGENT_DOCKERIGNORE_PATH.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    }

    for expected in (".venv/", "__pycache__/", ".pytest_cache/", ".ruff_cache/", "tests/"):
        assert expected in entries, expected


def test_env_example_defines_agent_variables_without_real_secrets() -> None:
    content = ENV_EXAMPLE_PATH.read_text(encoding="utf-8")
    keys = {
        line.split("=", 1)[0].strip()
        for line in content.splitlines()
        if line.strip() and not line.strip().startswith("#") and "=" in line
    }

    for expected in (
        "MALL_AGENT_MODEL_MODE",
        "MALL_AGENT_OPENAI_BASE_URL",
        "MALL_AGENT_OPENAI_API_KEY",
        "MALL_AGENT_OPENAI_MODEL",
        "AGENT_PORT",
    ):
        assert expected in keys, expected

    for marker in ("sk-", "Bearer "):
        assert marker not in content, marker


# --------------------------------------------------------------------------- #
# 环境校验脚本：存在性、重复定义检测、输出脱敏
# --------------------------------------------------------------------------- #


def test_agent_env_check_scripts_exist() -> None:
    assert CHECK_SCRIPT_PATH.is_file(), "缺少 PowerShell 环境校验脚本"
    assert BASH_SCRIPT_PATH.is_file(), "缺少 Bash 环境校验脚本"


@pytest.mark.parametrize(
    "script_path",
    [CHECK_SCRIPT_PATH, BASH_SCRIPT_PATH],
    ids=["powershell", "bash"],
)
def test_agent_env_check_scripts_detect_duplicate_definitions(script_path: Path) -> None:
    content = script_path.read_text(encoding="utf-8")

    assert "重复定义" in content
    assert "拒绝只取最后一个值" in content
    # 必须先统计出现次数，不能靠哈希表覆盖后继续通过
    assert "count_occurrences" in content or "duplicateKeys" in content


@pytest.mark.parametrize(
    "script_path",
    [CHECK_SCRIPT_PATH, BASH_SCRIPT_PATH],
    ids=["powershell", "bash"],
)
def test_agent_env_check_scripts_cover_required_variables_for_duplicates(
    script_path: Path,
) -> None:
    content = script_path.read_text(encoding="utf-8")

    for name in DUPLICATE_CHECKED_VARIABLES:
        assert name in content, name


@pytest.mark.parametrize(
    "script_path",
    [CHECK_SCRIPT_PATH, BASH_SCRIPT_PATH],
    ids=["powershell", "bash"],
)
def test_agent_env_check_scripts_never_print_variable_values(script_path: Path) -> None:
    content = script_path.read_text(encoding="utf-8")

    # 只允许输出变量名、固定提示与失败原因，不允许把变量值写入输出
    assert "只输出变量名" in content or "不输出任何变量值" in content

    printed: set[str] = set()
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped.startswith(("echo", "Write-Host")):
            continue
        for match in re.finditer(r"\$\{?([A-Za-z_][A-Za-z0-9_]*)", stripped):
            printed.add(match.group(1))

    unexpected = printed - ALLOWED_OUTPUT_VARIABLES
    assert unexpected == set(), f"输出语句疑似打印变量值：{sorted(unexpected)}"


@pytest.mark.parametrize(
    "script_path",
    [CHECK_SCRIPT_PATH, BASH_SCRIPT_PATH],
    ids=["powershell", "bash"],
)
def test_agent_env_check_scripts_do_not_reference_unsupported_parameter(
    script_path: Path,
) -> None:
    assert UNSUPPORTED_PARAMETER not in script_path.read_text(encoding="utf-8")


def test_plan_does_not_reference_unsupported_parameter() -> None:
    content = PLAN_PATH.read_text(encoding="utf-8")

    assert UNSUPPORTED_PARAMETER not in content
    assert "check-agent-env.ps1 -EnvFile .env.example" in content
    assert "bash document/docker/check-agent-env.sh .env.example" in content


def test_plan_documents_expected_exit_code_for_env_example() -> None:
    content = PLAN_PATH.read_text(encoding="utf-8")
    marker = "bash document/docker/check-agent-env.sh .env.example"

    assert marker in content
    window = content[content.index(marker) : content.index(marker) + 400]
    assert "退出码 1" in window or "exit 1" in window or "= 1" in window


# --------------------------------------------------------------------------- #
# 环境校验脚本：真实退出码矩阵（子进程执行）
# --------------------------------------------------------------------------- #


def _candidate_bash_paths() -> list[str]:
    candidates: list[str] = []
    for name in ("bash", "bash.exe"):
        found = shutil.which(name)
        if found:
            candidates.append(found)

    git = shutil.which("git")
    if git:
        git_root = Path(git).resolve().parent.parent
        candidates.append(str(git_root / "bin" / "bash.exe"))
        candidates.append(str(git_root / "usr" / "bin" / "bash.exe"))

    for env_name in ("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"):
        root = os.environ.get(env_name)
        if root:
            candidates.append(str(Path(root) / "Git" / "bin" / "bash.exe"))

    unique: list[str] = []
    for item in candidates:
        if item not in unique:
            unique.append(item)
    return unique


@lru_cache(maxsize=1)
def _bash_executable() -> str | None:
    """找到真正可用的 Bash：Windows 自带的 bash.exe 可能是不可用的 WSL 启动器。"""

    for candidate in _candidate_bash_paths():
        if not Path(candidate).is_file():
            continue
        try:
            probe = subprocess.run(
                [candidate, "-c", "echo bash-ready"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=30,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if probe.returncode == 0 and "bash-ready" in probe.stdout:
            return candidate
    return None


@lru_cache(maxsize=1)
def _powershell_executable() -> str | None:
    for name in ("powershell", "powershell.exe", "pwsh", "pwsh.exe"):
        found = shutil.which(name)
        if not found:
            continue
        try:
            probe = subprocess.run(
                [found, "-NoProfile", "-Command", "exit 0"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=60,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if probe.returncode == 0:
            return found
    return None


def _posix_path(path: Path) -> str:
    drive = path.drive.rstrip(":")
    rest = str(path)[len(path.drive) :].replace("\\", "/")
    return f"/{drive.lower()}{rest}" if drive else rest


def _run_env_check(runner: str, env_file: Path) -> subprocess.CompletedProcess:
    if runner == "powershell":
        executable = _powershell_executable()
        assert executable is not None
        return subprocess.run(
            [
                executable,
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(CHECK_SCRIPT_PATH),
                "-EnvFile",
                str(env_file),
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
            check=False,
        )

    executable = _bash_executable()
    assert executable is not None
    return subprocess.run(
        [executable, _posix_path(BASH_SCRIPT_PATH), _posix_path(env_file)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=120,
        check=False,
    )


@pytest.fixture(params=SCRIPT_NAMES)
def env_check_runner(request: pytest.FixtureRequest) -> str:
    if request.param == "powershell" and _powershell_executable() is None:
        pytest.skip("当前环境没有可用的 PowerShell")
    if request.param == "bash" and _bash_executable() is None:
        pytest.skip("当前环境没有可用的 Bash")
    return request.param


def _write_env_file(
    tmp_path: Path,
    name: str,
    content: str,
    *,
    with_bom: bool = False,
) -> Path:
    target = tmp_path / name
    target.write_text(content, encoding="utf-8-sig" if with_bom else "utf-8")
    return target


def _stub_env(*extra: str, api_key: str = "") -> str:
    lines = [
        "MALL_AGENT_MODEL_MODE=stub",
        f"MALL_AGENT_OPENAI_BASE_URL={TEST_BASE_URL}",
        f"MALL_AGENT_OPENAI_MODEL={TEST_MODEL_NAME}",
        f"MALL_AGENT_OPENAI_API_KEY={api_key}",
        "AGENT_PORT=8086",
        "MALL_AGENT_LOG_LEVEL=INFO",
        "MALL_AGENT_PORTAL_BASE_URL=http://localhost:8085",
        "MALL_AGENT_REDIS_URL=redis://localhost:6379/0",
        *extra,
    ]
    return "\n".join(lines) + "\n"


def _openai_env(*extra: str, base_url: str = TEST_BASE_URL, api_key: str = TEST_API_KEY) -> str:
    lines = [
        "MALL_AGENT_MODEL_MODE=openai",
        f"MALL_AGENT_OPENAI_BASE_URL={base_url}",
        f"MALL_AGENT_OPENAI_MODEL={TEST_MODEL_NAME}",
        f"MALL_AGENT_OPENAI_API_KEY={api_key}",
        "AGENT_PORT=8086",
        "MALL_AGENT_LOG_LEVEL=INFO",
        *extra,
    ]
    return "\n".join(lines) + "\n"


def test_env_example_openai_placeholder_config_fails(env_check_runner: str) -> None:
    result = _run_env_check(env_check_runner, ENV_EXAMPLE_PATH)

    assert result.returncode == 1, f"退出码={result.returncode}"


def test_stub_mode_allows_empty_api_key(env_check_runner: str, tmp_path: Path) -> None:
    env_file = _write_env_file(tmp_path, "stub.env", _stub_env())

    result = _run_env_check(env_check_runner, env_file)

    assert result.returncode == 0, f"退出码={result.returncode}"


def test_openai_mode_with_local_test_key_passes(env_check_runner: str, tmp_path: Path) -> None:
    env_file = _write_env_file(tmp_path, "openai.env", _openai_env())

    result = _run_env_check(env_check_runner, env_file)

    assert result.returncode == 0, f"退出码={result.returncode}"
    assert TEST_API_KEY not in (result.stdout + result.stderr)


def test_utf8_bom_env_file_is_handled(env_check_runner: str, tmp_path: Path) -> None:
    env_file = _write_env_file(tmp_path, "bom.env", _openai_env(), with_bom=True)

    result = _run_env_check(env_check_runner, env_file)

    assert result.returncode == 0, f"退出码={result.returncode}"


@pytest.mark.parametrize("duplicated", DUPLICATE_CHECKED_VARIABLES)
def test_duplicate_variable_definition_fails(
    env_check_runner: str,
    tmp_path: Path,
    duplicated: str,
) -> None:
    if duplicated == "AGENT_PORT":
        content = _stub_env("AGENT_PORT=9099")
    elif duplicated == "MALL_AGENT_MODEL_MODE":
        content = _stub_env("MALL_AGENT_MODEL_MODE=stub")
    elif duplicated == "MALL_AGENT_OPENAI_MODEL":
        content = _stub_env(f"MALL_AGENT_OPENAI_MODEL={TEST_MODEL_NAME}")
    elif duplicated == "MALL_AGENT_OPENAI_API_KEY":
        content = _openai_env(f"MALL_AGENT_OPENAI_API_KEY={TEST_API_KEY}")
    else:
        content = _openai_env(f"MALL_AGENT_OPENAI_BASE_URL={TEST_BASE_URL}")

    env_file = _write_env_file(tmp_path, "duplicate.env", content)

    result = _run_env_check(env_check_runner, env_file)

    assert result.returncode == 1, f"退出码={result.returncode}"
    assert TEST_API_KEY not in (result.stdout + result.stderr)


def test_invalid_model_mode_fails(env_check_runner: str, tmp_path: Path) -> None:
    content = _openai_env().replace(
        "MALL_AGENT_MODEL_MODE=openai", "MALL_AGENT_MODEL_MODE=anthropic"
    )
    env_file = _write_env_file(tmp_path, "bad-mode.env", content)

    assert _run_env_check(env_check_runner, env_file).returncode == 1


@pytest.mark.parametrize(
    "base_url",
    [
        "ftp://model.local.test/v1",
        "model.local.test/v1",
        "https://model.local.test/v1?query=1",
        "https://model.local.test/v1?",
        "https://model.local.test/v1#fragment",
        "https://model.local.test/v1#",
        "https://user:password@model.local.test/v1",
        "https://model.local.test/path with space",
        '" https://api.deepseek.com "',
        "https://:443/v1",
        "https://model.local.test:0/v1",
        "https://model.local.test:70000/v1",
        "https://model.local.test:invalid/v1",
        "https://model.local.test:/v1",
        "https://[::::]/v1",
        "https://[1:2:3:4:5:6:7:8:9]/v1",
        "https://[fe80::1%25Ethernet]/v1",
        "https://gateway[bad].test/v1",
    ],
)
def test_invalid_base_url_fails(env_check_runner: str, tmp_path: Path, base_url: str) -> None:
    env_file = _write_env_file(tmp_path, "bad-url.env", _openai_env(base_url=base_url))

    assert _run_env_check(env_check_runner, env_file).returncode == 1


@pytest.mark.parametrize(
    "base_url",
    [
        "https://api.deepseek.com",
        "https://api.deepseek.com/",
        "HTTPS://api.deepseek.com",
        "https://api.openai.com/v1",
        "http://[::1]:8086/v1/",
        "https://gateway.local.test/compatible/v1/",
        "https://gateway.local.test/compatible%20path/v1/",
        "https://gateway.local.test/compatible%2Fv1/",
    ],
)
def test_provider_base_urls_are_accepted(
    env_check_runner: str, tmp_path: Path, base_url: str
) -> None:
    env_file = _write_env_file(tmp_path, "valid-provider-url.env", _openai_env(base_url=base_url))

    result = _run_env_check(env_check_runner, env_file)

    assert result.returncode == 0, f"退出码={result.returncode}\n{result.stdout}\n{result.stderr}"
    assert TEST_API_KEY not in (result.stdout + result.stderr)


@pytest.mark.parametrize("port", ["70000", "0", "not-a-port"])
def test_invalid_agent_port_fails(env_check_runner: str, tmp_path: Path, port: str) -> None:
    content = _stub_env().replace("AGENT_PORT=8086", f"AGENT_PORT={port}")
    env_file = _write_env_file(tmp_path, "bad-port.env", content)

    assert _run_env_check(env_check_runner, env_file).returncode == 1


def test_placeholder_api_key_fails_in_openai_mode(env_check_runner: str, tmp_path: Path) -> None:
    env_file = _write_env_file(
        tmp_path,
        "placeholder-key.env",
        _openai_env(api_key="<请填写模型服务 API Key>"),
    )

    assert _run_env_check(env_check_runner, env_file).returncode == 1


def test_empty_api_key_fails_in_openai_mode(env_check_runner: str, tmp_path: Path) -> None:
    env_file = _write_env_file(tmp_path, "empty-key.env", _openai_env(api_key=""))

    assert _run_env_check(env_check_runner, env_file).returncode == 1


def test_missing_env_file_returns_two(env_check_runner: str, tmp_path: Path) -> None:
    result = _run_env_check(env_check_runner, tmp_path / "missing.env")

    assert result.returncode == 2, f"退出码={result.returncode}"


# --------------------------------------------------------------------------- #
# Task 12：Compose 切换到 Java 运行实现（build / healthcheck / env / 构建上下文）
# --------------------------------------------------------------------------- #


@lru_cache(maxsize=1)
def _source_mall_agent_variables() -> tuple[str, ...]:
    """从 AgentProperties.java 实际读取 MALL_AGENT_* 常量值，避免白名单与源码漂移。"""

    content = AGENT_PROPERTIES_PATH.read_text(encoding="utf-8")
    return tuple(re.findall(r'"(MALL_AGENT_[A-Z0-9_]+)"', content))


def test_java_env_whitelist_constant_matches_agent_properties_source() -> None:
    from_source = _source_mall_agent_variables()

    assert len(MALL_AGENT_ENVIRONMENT_VARIABLES) == 23
    assert len(from_source) == 23, f"AgentProperties 中的 MALL_AGENT_* 常量数量异常：{from_source}"
    assert set(from_source) == set(MALL_AGENT_ENVIRONMENT_VARIABLES), (
        "测试常量列表与 AgentProperties 声明不一致："
        f"源码={sorted(from_source)} 常量={sorted(MALL_AGENT_ENVIRONMENT_VARIABLES)}"
    )


def test_java_build_reuses_app_dockerfile_from_repo_root(agent_service: dict) -> None:
    build = agent_service.get("build")
    assert isinstance(build, dict), "mall-shopping-agent 必须从仓库源码构建，而不是只依赖预构建镜像"

    assert build.get("context") in {".", "./"}, (
        f"build context 必须是仓库根目录，实际为 {build.get('context')!r}"
    )
    assert build.get("dockerfile") == "document/docker/Dockerfile.app", (
        f"必须复用通用 Java 构建文件，实际为 {build.get('dockerfile')!r}"
    )

    args = build.get("args") or {}
    assert args.get("MODULE") == "mall-agent"
    assert args.get("JAR_FILE") == "mall-agent-1.0-SNAPSHOT.jar"
    assert str(args.get("APP_PORT")) == "8086"

    for field in ("context", "dockerfile"):
        assert "Dockerfile.agent" not in str(build.get(field)), (
            "Python Dockerfile.agent 不应再作为 Compose 运行构建目标"
        )


def test_java_build_app_dockerfile_compiles_module_from_root_source() -> None:
    assert APP_DOCKERFILE_PATH.is_file(), "缺少 document/docker/Dockerfile.app"

    content = APP_DOCKERFILE_PATH.read_text(encoding="utf-8")

    assert "COPY mall-master/ ./" in content, "构建上下文是仓库根，必须复制 mall-master/"
    assert '-pl "${MODULE}" -am' in content, "多模块构建必须用 -pl / -am 编译目标模块及其依赖"
    assert "eclipse-temurin:17-jre" in content, "运行阶段必须是 Java 17 JRE 镜像"
    assert re.search(
        r"COPY[^\n]*--from=builder[^\n]*/build/\$\{MODULE\}/target/\$\{JAR_FILE\}[^\n]*/app/app\.jar",
        content,
    ), "必须把模块 jar 打成 Java 应用 /app/app.jar（允许附带 --chown 等归属标记）"
    assert "ENTRYPOINT" in content and "/app/app.jar" in content, "启动命令必须是 java -jar /app/app.jar"


def test_java_healthcheck_uses_curl_on_liveness_endpoint(agent_service: dict) -> None:
    healthcheck = agent_service["healthcheck"]
    command = " ".join(str(part) for part in healthcheck["test"])
    lowered = command.lower()

    assert "curl" in lowered, "Java 运行镜像通过 apt-get 安装 curl，健康探针应使用镜像内已有的 curl"
    assert "http://127.0.0.1:8086/health/live" in command
    assert "8086" in command
    assert "python" not in lowered, "Java 运行镜像不含 Python，健康探针不得依赖 Python"
    assert "urllib" not in lowered


def test_java_env_injects_exact_agent_properties_whitelist(agent_service: dict) -> None:
    environment = agent_service.get("environment")
    assert isinstance(environment, dict), (
        "mall-shopping-agent 的 environment 必须是显式键值映射，不能退化为列表或整体 env_file 注入"
    )

    keys = set(environment)

    missing = set(MALL_AGENT_ENVIRONMENT_VARIABLES) - keys
    assert missing == set(), f"Compose 未注入 AgentProperties 白名单变量：{sorted(missing)}"

    leaked = keys & FORBIDDEN_AGENT_ENV_KEYS
    assert leaked == set(), f"agent 容器不应拿到基础设施凭据：{sorted(leaked)}"
    assert "env_file" not in agent_service, "不要整体注入根 .env，避免把基础设施凭据带进 agent 容器"

    extra = keys - set(MALL_AGENT_ENVIRONMENT_VARIABLES) - ALLOWED_NON_WHITELIST_AGENT_ENV_KEYS
    assert extra == set(), f"agent 容器出现白名单外变量：{sorted(extra)}"


def test_root_dockerignore_excludes_worktrees_and_secrets() -> None:
    entries = {
        line.strip()
        for line in ROOT_DOCKERIGNORE_PATH.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    }

    for expected in (".worktrees/", ".git/", "**/target/", "**/node_modules/"):
        assert expected in entries, f"根 .dockerignore 缺少排除规则：{expected}"

    assert "**/.env" in entries, "本机 .env 不得进入构建上下文"
    assert "!**/.env.example" in entries, ".env.example 仍应保留在构建上下文中"


# --------------------------------------------------------------------------- #
# Task 12 返工：逐变量配置契约（固定 / 插值）与 .git 精确忽略
# --------------------------------------------------------------------------- #


def _env_example_agent_sections() -> tuple[dict[str, str], dict[str, str]]:
    """按 .env.example 的两段契约小标题，返回 (A 可调段, B 固定段) 的键值映射。"""

    adjustable: dict[str, str] = {}
    fixed: dict[str, str] = {}
    section: dict[str, str] | None = None

    for raw_line in ENV_EXAMPLE_PATH.read_text(encoding="utf-8").splitlines():
        stripped = raw_line.strip()
        if stripped == ENV_EXAMPLE_ADJUSTABLE_HEADER:
            section = adjustable
            continue
        if stripped == ENV_EXAMPLE_FIXED_HEADER:
            section = fixed
            continue
        if section is None or not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        name, _, value = stripped.partition("=")
        name = name.strip()
        if name.startswith("MALL_AGENT_"):
            section[name] = value.strip()

    return adjustable, fixed


def test_agent_env_contract_partitions_full_whitelist() -> None:
    interpolated = set(AGENT_COMPOSE_INTERPOLATED_VARIABLES)
    fixed = set(AGENT_COMPOSE_FIXED_VARIABLES)

    assert interpolated & fixed == set(), "同一变量不能同时属于可调与固定两类"
    assert interpolated | fixed == set(MALL_AGENT_ENVIRONMENT_VARIABLES), (
        "配置契约必须覆盖 AgentProperties 的全部 23 个白名单变量"
    )


def test_compose_agent_interpolated_variables_read_host_env(agent_service: dict) -> None:
    environment = agent_service["environment"]

    for name, default in AGENT_COMPOSE_INTERPOLATED_VARIABLES.items():
        assert environment[name] == f"${{{name}:-{default}}}", (
            f"{name} 属于 Compose 可调变量，必须写成 ${{{name}:-默认值}}，实际为 {environment[name]!r}"
        )


def test_compose_agent_fixed_variables_ignore_host_env(agent_service: dict) -> None:
    environment = agent_service["environment"]

    for name, expected in AGENT_COMPOSE_FIXED_VARIABLES.items():
        assert environment[name] == expected, (
            f"{name} 固定值应为 {expected!r}，实际为 {environment[name]!r}"
        )
        assert "${" not in environment[name], (
            f"{name} 属于 Compose 容器固定值，不得使用宿主 .env 插值，实际为 {environment[name]!r}"
        )


def test_compose_agent_request_timeout_stays_below_nginx_read_timeout() -> None:
    timeout = int(AGENT_COMPOSE_FIXED_VARIABLES["MALL_AGENT_REQUEST_TIMEOUT_SECONDS"])

    assert timeout < NGINX_AGENT_READ_TIMEOUT_SECONDS, (
        "MALL_AGENT_REQUEST_TIMEOUT_SECONDS 必须小于 Nginx 的 proxy_read_timeout，"
        "否则 Nginx 会先于 agent 自身超时返回 504"
    )
    nginx_conf = NGINX_CONF_PATH.read_text(encoding="utf-8")
    assert f"proxy_read_timeout    {NGINX_AGENT_READ_TIMEOUT_SECONDS}s;" in nginx_conf


def test_env_example_labels_each_agent_variable_by_contract() -> None:
    content = ENV_EXAMPLE_PATH.read_text(encoding="utf-8")

    assert ENV_EXAMPLE_ADJUSTABLE_HEADER in content, "缺少 Compose 可调段小标题"
    assert ENV_EXAMPLE_FIXED_HEADER in content, "缺少 Compose 容器固定值段小标题"
    assert "不通过宿主 .env 覆盖" in content, "必须逐项说明固定值不通过宿主 .env 覆盖"

    adjustable, fixed = _env_example_agent_sections()

    assert set(adjustable) == set(AGENT_COMPOSE_INTERPOLATED_VARIABLES), (
        "Compose 可调段必须正好列出可调变量，差异="
        f"{sorted(set(adjustable) ^ set(AGENT_COMPOSE_INTERPOLATED_VARIABLES))}"
    )
    assert set(fixed) == set(AGENT_COMPOSE_FIXED_VARIABLES), (
        "Compose 容器固定值段必须正好列出固定变量，差异="
        f"{sorted(set(fixed) ^ set(AGENT_COMPOSE_FIXED_VARIABLES))}"
    )
    assert fixed["MALL_AGENT_REQUEST_TIMEOUT_SECONDS"] == "35", "固定段中的请求超时必须是 35"


def test_env_example_fixed_values_match_contract_with_trusted_proxy_exception() -> None:
    """.env.example 的 B 段必须列出可信代理，且为唯一 host-only 空值例外。

    该例外保证把 .env.example 复制成 .env 后不经 Compose 直接运行 Java 时默认不信任代理；
    Compose 容器由 docker-compose.yml 固定注入 Nginx 静态地址，不受宿主 .env 覆盖。
    注意：B 段其它取值（如 PORTAL_BASE_URL / REDIS_URL）本就按「宿主机直跑参考」写成
    localhost 地址，与 Compose 容器内服务名不同，因此本用例不逐项比对 B 段取值。
    """

    content = ENV_EXAMPLE_PATH.read_text(encoding="utf-8")
    _, fixed = _env_example_agent_sections()

    assert TRUSTED_PROXY_VARIABLE in fixed, (
        f".env.example 固定段必须列出 {TRUSTED_PROXY_VARIABLE}，即使取值为空"
    )
    assert fixed[TRUSTED_PROXY_VARIABLE] == ENV_EXAMPLE_HOST_ONLY_EMPTY_FIXED[TRUSTED_PROXY_VARIABLE], (
        f"{TRUSTED_PROXY_VARIABLE} 在 .env.example 中必须留空"
        "（宿主机直跑参考的安全默认），Compose 固定值 172.21.240.2 写在 docker-compose.yml"
    )
    for marker in TRUSTED_PROXY_ENV_EXAMPLE_MARKERS:
        assert marker in content, f"缺少可信代理 host-only 例外说明：{marker}"


def test_root_dockerignore_ignores_git_file_and_directory() -> None:
    entries = {
        line.strip()
        for line in ROOT_DOCKERIGNORE_PATH.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    }

    # Windows linked-worktree 下 .git 是文件而不是目录，只有 `.git/` 规则无法排除它
    assert ".git" in entries, "根 .dockerignore 必须精确忽略 .git 文件（linked worktree）"
    assert ".git/" in entries, "根 .dockerignore 应保留对 .git 目录的忽略"


def _raw_agent_healthcheck_comment() -> str:
    """从 docker-compose.yml 原文提取 mall-shopping-agent.healthcheck 的注释块。

    YAML 注释不会被 ``yaml.safe_load`` 解析，因此这里按行读取原文：定位服务块内的
    ``healthcheck:``，取其下连续的 6 空格缩进注释行（遇到 ``test:`` 等指令行即停止）。
    """

    lines = COMPOSE_PATH.read_text(encoding="utf-8").splitlines()

    service_index = next(
        i for i, line in enumerate(lines) if line.rstrip() == f"  {SERVICE_NAME}:"
    )
    healthcheck_index = next(
        i
        for i in range(service_index + 1, len(lines))
        if lines[i].rstrip() == "    healthcheck:"
    )

    comment_lines: list[str] = []
    for line in lines[healthcheck_index + 1 :]:
        if not line.startswith("      #"):
            break
        comment_lines.append(line.strip())

    return "\n".join(comment_lines)


def test_compose_healthcheck_never_probes_not_ready_endpoint(agent_service: dict) -> None:
    """Compose healthcheck 只能探 /health/live。

    ``/health/ready`` 由真实探针决定：Redis ``PING`` 与 mall-portal 只读查询均健康时返回
    200，任一依赖失败即 503；把它写进 healthcheck 会在依赖抖动时把容器判成 unhealthy，
    因此这里保护「容器探针只用 liveness」这一结构性契约，而非文档措辞。
    """

    healthcheck = agent_service["healthcheck"]
    command = " ".join(str(part) for part in healthcheck["test"])

    assert "/health/ready" not in command, (
        "依赖失败时 /health/ready 返回 503，不得用作容器存活探针"
    )
    assert "/health/live" in command


def test_agent_healthcheck_comment_describes_real_readiness_probes() -> None:
    """healthcheck 注释不得再宣称探针是占位实现 / ready 恒 503（与当前源码不符）。"""

    comment = _raw_agent_healthcheck_comment()

    assert comment, "mall-shopping-agent.healthcheck 必须保留说明性注释"

    for stale in ("固定返回 false", "恒为 503", "占位"):
        assert stale not in comment, f"healthcheck 注释仍在描述已过时的实现：{stale}"

    # 注释必须保留真实契约：只探存活接口，避免依赖异常时容器被判 unhealthy
    assert "/health/live" in comment
    assert "unhealthy" in comment


# --------------------------------------------------------------------------- #
# 安全边界：Agent 专用网络、可信代理边界与可选运行用户
# --------------------------------------------------------------------------- #


def _service_networks(compose: dict, service: str) -> dict[str, dict]:
    """把某服务的 networks 归一为 {网络名: 网络级配置}，兼容 list 与 map 两种写法。"""

    networks = (compose.get("services", {}).get(service, {}) or {}).get("networks")
    if isinstance(networks, dict):
        return {name: (config or {}) for name, config in networks.items()}
    if isinstance(networks, list):
        return {name: {} for name in networks}
    return {}


def test_agent_runs_as_non_root_user(agent_service: dict) -> None:
    """只有 mall-shopping-agent 以固定非 root UID/GID 运行。"""

    assert agent_service.get("user") == AGENT_RUN_USER, (
        f"mall-shopping-agent 必须以 {AGENT_RUN_USER} 运行，实际为 {agent_service.get('user')!r}"
    )


def test_shared_app_dockerfile_other_services_keep_default_root_run_user(compose: dict) -> None:
    """复用 Dockerfile.app 的三个服务不得改变运行身份与 HOME：既不写 user，也不传运行用户 / HOME ARG。

    runtime 阶段 USER 与 HOME 都由 ARG 选择且默认与原 eclipse-temurin root 镜像一致
    （USER=0、HOME=/root），因此这三个服务默认保持与迁移前完全一致的运行环境；
    只有 mall-shopping-agent 通过 build.args 显式选择 mallapp 与 /app。
    """

    for name in ("mall-admin", "mall-search", "mall-portal"):
        service = compose["services"][name] or {}
        assert "user" not in service, (
            f"{name} 不应在 Compose 层覆盖运行用户，运行用户应由 Dockerfile.app 的 ARG 默认值决定"
        )
        build_args = (service.get("build") or {}).get("args") or {}
        assert APP_RUN_USER_ARG not in build_args, (
            f"{name} 不得传入 {APP_RUN_USER_ARG}，否则会改变未经容器验证的运行身份"
        )
        assert APP_HOME_ARG not in build_args, (
            f"{name} 不得传入 {APP_HOME_ARG}，否则会把 HOME 从 root 镜像默认的 "
            f"{APP_HOME_DEFAULT} 改为其它目录"
        )


def test_agent_networks_exclude_mall_net(compose: dict) -> None:
    """Agent 只能接入专用代理网络与后端网络，必须从共享的 mall-net 移除。

    专用网络只提供服务名 / DNS 层面的分段，不构成「看不到数据库」的强隔离：
    Docker Desktop 下主栈服务经宿主机已发布端口、以及共享的无认证 Redis 仍可达。
    """

    names = set(_service_networks(compose, SERVICE_NAME))
    assert names == {AGENT_PROXY_NETWORK, AGENT_BACKEND_NETWORK}, (
        f"Agent 只应接入 {AGENT_PROXY_NETWORK} 与 {AGENT_BACKEND_NETWORK}，实际为 {sorted(names)}"
    )
    assert "mall-net" not in names, (
        "Agent 不得接入共享 mall-net：专用网络只提供按服务名 / DNS 的分段，"
        "把 Agent 放回共享网络会失去这层分段边界"
    )


def test_agent_proxy_network_subnet_and_static_addresses(compose: dict) -> None:
    """agent-proxy-net 必须是固定子网，Nginx 与 Agent 使用固定静态地址。"""

    networks = compose.get("networks") or {}
    proxy = networks.get(AGENT_PROXY_NETWORK) or {}
    configs = ((proxy.get("ipam") or {}).get("config")) or []
    subnets = [config.get("subnet") for config in configs]

    assert subnets == [AGENT_PROXY_SUBNET], (
        f"{AGENT_PROXY_NETWORK} 子网必须是 {AGENT_PROXY_SUBNET}，实际为 {subnets}"
    )

    agent_nets = _service_networks(compose, SERVICE_NAME)
    nginx_nets = _service_networks(compose, "nginx")
    assert agent_nets[AGENT_PROXY_NETWORK].get("ipv4_address") == AGENT_PROXY_STATIC_IP
    assert nginx_nets[AGENT_PROXY_NETWORK].get("ipv4_address") == NGINX_PROXY_STATIC_IP


def test_agent_backend_network_is_plain_bridge_shared_only_with_redis_and_portal(compose: dict) -> None:
    """agent-backend-net 是普通非 internal bridge，只接入 Agent / Redis / mall-portal。"""

    backend = (compose.get("networks") or {}).get(AGENT_BACKEND_NETWORK) or {}
    assert backend.get("driver", "bridge") == "bridge"
    assert backend.get("internal") in (None, False), (
        "agent-backend-net 不能是 internal，否则 Agent 失去访问模型 API 的外网能力"
    )

    shared = {
        name
        for name in compose["services"]
        if AGENT_BACKEND_NETWORK in _service_networks(compose, name)
    }
    assert shared == {SERVICE_NAME, "redis", "mall-portal"}, (
        f"agent-backend-net 只应接入 Agent/Redis/mall-portal，实际为 {sorted(shared)}"
    )


def test_redis_and_portal_keep_mall_net_and_join_agent_backend_net(compose: dict) -> None:
    """Redis 与 mall-portal 保留 mall-net，同时加入 agent-backend-net 供 Agent 访问。"""

    for name in ("redis", "mall-portal"):
        names = set(_service_networks(compose, name))
        assert "mall-net" in names, f"{name} 必须保留在 mall-net 上"
        assert AGENT_BACKEND_NETWORK in names, f"{name} 必须加入 {AGENT_BACKEND_NETWORK}"


def test_nginx_keeps_mall_net_and_joins_agent_proxy_net(compose: dict) -> None:
    """Nginx 保留 mall-net（继续代理商城服务）并加入 agent-proxy-net。"""

    names = set(_service_networks(compose, "nginx"))
    assert names == {"mall-net", AGENT_PROXY_NETWORK}, (
        f"Nginx 应只接入 mall-net 与 {AGENT_PROXY_NETWORK}，实际为 {sorted(names)}"
    )


def test_agent_trusted_proxy_ip_matches_nginx_static_address(
    agent_service: dict, compose: dict
) -> None:
    """Agent 配置的唯一可信代理地址必须与 Nginx 在代理网络上的静态地址逐字一致。"""

    environment = agent_service.get("environment") or {}
    assert environment.get(TRUSTED_PROXY_VARIABLE) == NGINX_PROXY_STATIC_IP, (
        f"{TRUSTED_PROXY_VARIABLE} 必须等于 Nginx 静态地址 {NGINX_PROXY_STATIC_IP}，"
        f"实际为 {environment.get(TRUSTED_PROXY_VARIABLE)!r}"
    )
    nginx_nets = _service_networks(compose, "nginx")
    assert nginx_nets[AGENT_PROXY_NETWORK].get("ipv4_address") == NGINX_PROXY_STATIC_IP


def test_agent_proxy_static_ips_are_inside_declared_subnet(compose: dict) -> None:
    """静态地址必须落在声明的代理子网内，且 Nginx 与 Agent 地址互不相同。"""

    subnet = ipaddress.ip_network(AGENT_PROXY_SUBNET)
    nginx_ip = ipaddress.ip_address(NGINX_PROXY_STATIC_IP)
    agent_ip = ipaddress.ip_address(AGENT_PROXY_STATIC_IP)

    assert nginx_ip in subnet, f"{NGINX_PROXY_STATIC_IP} 不在 {AGENT_PROXY_SUBNET} 内"
    assert agent_ip in subnet, f"{AGENT_PROXY_STATIC_IP} 不在 {AGENT_PROXY_SUBNET} 内"
    assert nginx_ip != agent_ip


def test_explicit_compose_subnets_do_not_overlap(compose: dict) -> None:
    """所有显式声明的 Compose 子网两两不重叠。"""

    subnets: list[tuple[str, ipaddress._BaseNetwork]] = []
    for name, network in (compose.get("networks") or {}).items():
        for config in ((network or {}).get("ipam") or {}).get("config") or []:
            if config.get("subnet"):
                subnets.append((name, ipaddress.ip_network(config["subnet"])))

    for index in range(len(subnets)):
        for other in range(index + 1, len(subnets)):
            assert not subnets[index][1].overlaps(subnets[other][1]), (
                f"{subnets[index][0]} 与 {subnets[other][0]} 的子网重叠"
            )


# --------------------------------------------------------------------------- #
# CORS 部署默认值：同源经 Nginx 不需 CORS，默认 fail-closed 且不得通配兜底
# --------------------------------------------------------------------------- #


def test_compose_cors_variable_has_no_wildcard_fallback() -> None:
    """docker-compose.yml 不得为 CORS 提供 ``:-*`` 通配兜底，默认必须是空插值。"""

    content = COMPOSE_PATH.read_text(encoding="utf-8")

    assert f"${{{CORS_VARIABLE}:-*}}" not in content, (
        "CORS 变量不得使用 :-* 通配兜底，否则空值会被渲染成放行任意来源"
    )
    assert f"${{{CORS_VARIABLE}:-}}" in content, (
        f"CORS 变量必须写成 ${{{CORS_VARIABLE}:-}}（默认留空、不授权跨域）"
    )


def test_env_example_cors_default_is_empty() -> None:
    """`.env.example` 中 CORS 默认为空，不得再把 ``*`` 当作默认值。"""

    adjustable, _ = _env_example_agent_sections()

    assert CORS_VARIABLE in adjustable, f"{CORS_VARIABLE} 必须位于 Compose 可调段"
    assert adjustable[CORS_VARIABLE] == "", (
        f"{CORS_VARIABLE} 默认必须留空（不授权任何跨域来源），实际为 {adjustable[CORS_VARIABLE]!r}"
    )
    assert "*" not in adjustable[CORS_VARIABLE]


def test_env_example_cors_comment_documents_fail_closed_default() -> None:
    """`.env.example` 注释必须说明默认不授权跨域、跨域直连需显式填写精确来源。"""

    content = ENV_EXAMPLE_PATH.read_text(encoding="utf-8")

    for marker in CORS_ENV_EXAMPLE_COMMENT_MARKERS:
        assert marker in content, f"CORS 注释缺少说明：{marker}"


@lru_cache(maxsize=1)
def _compose_executable() -> str | None:
    """返回可用的 ``docker``（含 compose 插件）可执行文件，否则 None。"""

    found = shutil.which("docker")
    if not found:
        return None
    try:
        probe = subprocess.run(
            [found, "compose", "version"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=60,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return found if probe.returncode == 0 else None


def _require_compose() -> str:
    executable = _compose_executable()
    if executable is None:
        pytest.skip("当前环境没有可用的 docker compose")
    return executable


def _run_compose_config(
    env_file: Path,
    profiles: tuple[str, ...] = (),
    *,
    quiet: bool = False,
) -> subprocess.CompletedProcess:
    command = [_require_compose(), "compose", "--env-file", str(env_file)]
    for profile in profiles:
        command += ["--profile", profile]
    command.append("config")
    if quiet:
        command.append("--quiet")

    return subprocess.run(
        command,
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=180,
        check=False,
    )


def _rendered_agent_cors(env_file: Path, profiles: tuple[str, ...]) -> str:
    result = _run_compose_config(env_file, profiles)

    assert result.returncode == 0, f"docker compose config 失败：{result.stderr}"
    rendered = yaml.safe_load(result.stdout)
    environment = rendered["services"][SERVICE_NAME]["environment"]

    assert CORS_VARIABLE in environment, "渲染结果缺少 CORS 变量"
    return environment[CORS_VARIABLE]


def test_compose_config_default_profile_is_valid() -> None:
    """默认分组（不带 --profile）的 ``config --quiet`` 必须保持退出码 0。"""

    assert _run_compose_config(ENV_EXAMPLE_PATH, quiet=True).returncode == 0


def test_compose_config_all_profiles_is_valid() -> None:
    """全 profile（app + edge + observability）的 ``config --quiet`` 必须保持退出码 0。"""

    result = _run_compose_config(
        ENV_EXAMPLE_PATH,
        ("app", "edge", "observability"),
        quiet=True,
    )

    assert result.returncode == 0


def test_rendered_cors_defaults_to_empty_in_app_profile() -> None:
    """以 `.env.example` 渲染 ``app`` profile 时，CORS 必须为空（不授权跨域）。"""

    rendered = _rendered_agent_cors(ENV_EXAMPLE_PATH, ("app",))

    assert rendered == "", f"默认渲染 CORS 应为空，实际为 {rendered!r}"
    assert rendered != "*", "默认渲染不得退化为通配来源 *"


def test_rendered_cors_defaults_to_empty_in_all_profiles() -> None:
    """全 profile 下以 `.env.example` 渲染时，CORS 同样必须为空。"""

    rendered = _rendered_agent_cors(ENV_EXAMPLE_PATH, ("app", "edge", "observability"))

    assert rendered == "", f"全 profile 渲染 CORS 应为空，实际为 {rendered!r}"


def test_rendered_cors_keeps_explicit_exact_origins(tmp_path: Path) -> None:
    """显式填写的精确来源必须原样保留（用临时 env 文件，绝不改根 .env）。"""

    env_content = ENV_EXAMPLE_PATH.read_text(encoding="utf-8")
    updated = re.sub(
        rf"(?m)^{CORS_VARIABLE}=.*$",
        f"{CORS_VARIABLE}={CORS_EXPLICIT_ORIGINS}",
        env_content,
    )
    assert f"{CORS_VARIABLE}={CORS_EXPLICIT_ORIGINS}" in updated

    temp_env = tmp_path / "cors-explicit.env"
    temp_env.write_text(updated, encoding="utf-8")

    rendered = _rendered_agent_cors(temp_env, ("app",))

    assert rendered == CORS_EXPLICIT_ORIGINS, (
        f"显式精确来源应被原样保留，实际为 {rendered!r}"
    )


# --------------------------------------------------------------------------- #
# 安全隔离：真实 docker compose config 渲染输出断言（优先于纯文本 grep）
# --------------------------------------------------------------------------- #


def _rendered_all_services() -> dict:
    result = _run_compose_config(ENV_EXAMPLE_PATH, ("app", "edge"))

    assert result.returncode == 0, f"docker compose config 渲染失败：{result.stderr}"
    return yaml.safe_load(result.stdout)


def test_rendered_agent_isolation_matches_static_contract() -> None:
    """渲染后的网络、静态地址、可信代理与运行用户必须与静态契约一致。"""

    rendered = _rendered_all_services()

    agent = rendered["services"][SERVICE_NAME]
    assert agent.get("user") == AGENT_RUN_USER

    agent_networks = agent.get("networks") or {}
    assert set(agent_networks) == {AGENT_PROXY_NETWORK, AGENT_BACKEND_NETWORK}, (
        f"渲染后的 Agent 网络为 {sorted(agent_networks)}"
    )
    assert agent_networks[AGENT_PROXY_NETWORK].get("ipv4_address") == AGENT_PROXY_STATIC_IP
    assert agent["environment"][TRUSTED_PROXY_VARIABLE] == NGINX_PROXY_STATIC_IP

    nginx_networks = rendered["services"]["nginx"].get("networks") or {}
    assert set(nginx_networks) == {"mall-net", AGENT_PROXY_NETWORK}
    assert nginx_networks[AGENT_PROXY_NETWORK].get("ipv4_address") == NGINX_PROXY_STATIC_IP

    proxy_configs = ((rendered["networks"][AGENT_PROXY_NETWORK].get("ipam") or {}).get("config")) or []
    assert [config.get("subnet") for config in proxy_configs] == [AGENT_PROXY_SUBNET]


def test_rendered_agent_network_does_not_contain_mall_net() -> None:
    """渲染后 Agent 的网络集合不得包含 mall-net，Redis/portal 不得丢失共享网络。"""

    rendered = _rendered_all_services()

    agent_networks = set(rendered["services"][SERVICE_NAME].get("networks") or {})
    assert "mall-net" not in agent_networks

    for name in ("redis", "mall-portal"):
        names = set(rendered["services"][name].get("networks") or {})
        assert names >= {"mall-net", AGENT_BACKEND_NETWORK}, f"{name} 渲染网络为 {sorted(names)}"


# --------------------------------------------------------------------------- #
# 通用 Java 运行镜像：可选的运行用户、app.jar 归属与四服务复用契约
# --------------------------------------------------------------------------- #
# Dockerfile.app 被四个 Java 服务共用：runtime 阶段始终创建固定的 mallapp
# （UID:GID 10001:10001）并把 /app 与 app.jar 归属它，但最终 USER 与 ENV HOME 均由
# runtime 阶段的 ARG 选择，默认与原 eclipse-temurin root 镜像一致（USER=root、HOME=/root）
# ——即 mall-admin / mall-search / mall-portal 默认保持与迁移前一致的运行身份与环境，
# 只有 mall-shopping-agent 在 Compose build.args 传入 mallapp 与 /app，
# 才使 Agent 镜像 Config.User=mallapp、HOME=/app（与其 passwd home 一致）。
# 这些用例解析 runtime 阶段的有效指令（拼接续行、丢弃注释），而不是纯文本 grep。

APP_RUN_USER_NAME = "mallapp"
APP_RUN_UID = "10001"
APP_RUN_GID = "10001"
APP_RUN_USER_SPEC = f"{APP_RUN_UID}:{APP_RUN_GID}"
# 选择运行用户的 runtime ARG：默认 root（其余三个服务沿用原运行身份），
# 只有 mall-shopping-agent 通过 Compose build.args 传入 mallapp。
APP_RUN_USER_ARG = "APP_RUN_USER"
APP_RUN_USER_DEFAULT = "root"
APP_RUN_USER_AGENT_VALUE = APP_RUN_USER_NAME
# 运行用户 mallapp 的 passwd home：复用已由非递归 chown 赋权的工作目录 /app
APP_RUN_HOME = "/app"
# 进程 HOME 的 runtime ARG：默认 /root，保持 eclipse-temurin:17-jre root 镜像的 HOME
# （docker run 实测 BASE_HOME=/root、USER=0），使 mall-admin / mall-search / mall-portal
# 的运行环境与迁移前完全一致；只有 mall-shopping-agent 通过 Compose build.args 传入
# /app，与 mallapp 的 passwd home 一致。
APP_HOME_ARG = "APP_HOME"
APP_HOME_DEFAULT = "/root"
APP_HOME_AGENT_VALUE = APP_RUN_HOME
# 复用 Dockerfile.app 的四个 Java 服务（含 Compose service key mall-shopping-agent）
SHARED_APP_DOCKERFILE_SERVICES = (
    "mall-admin",
    "mall-search",
    "mall-portal",
    SERVICE_NAME,
)


def _logical_dockerfile_lines(content: str) -> list[str]:
    """把 Dockerfile 解析为逻辑指令行：拼接续行、丢弃注释行与空行。"""

    logical: list[str] = []
    buffer = ""
    for raw in content.splitlines():
        stripped = raw.strip()
        if not buffer and (not stripped or stripped.startswith("#")):
            continue
        buffer = f"{buffer} {stripped}".strip() if buffer else stripped
        if buffer.endswith("\\"):
            buffer = buffer[:-1].rstrip()
            continue
        logical.append(buffer)
        buffer = ""
    if buffer:
        logical.append(buffer)
    return logical


def _dockerfile_instructions(content: str) -> list[tuple[str, str]]:
    """返回 (指令名大写, 参数) 列表，续行已合并。"""

    instructions: list[tuple[str, str]] = []
    for line in _logical_dockerfile_lines(content):
        parts = line.split(None, 1)
        instructions.append((parts[0].upper(), parts[1].strip() if len(parts) > 1 else ""))
    return instructions


def _runtime_stage_instructions() -> list[tuple[str, str]]:
    """返回 runtime 阶段（最后一个 ``AS runtime`` 的 FROM 段）的有效指令。"""

    stages: list[list[tuple[str, str]]] = []
    for instruction, args in _dockerfile_instructions(
        APP_DOCKERFILE_PATH.read_text(encoding="utf-8")
    ):
        if instruction == "FROM":
            stages.append([])
        assert stages, "Dockerfile.app 必须以 FROM 开始"
        stages[-1].append((instruction, args))

    runtime_stages = [
        stage
        for stage in stages
        if any(i == "FROM" and re.search(r"\bAS\s+runtime\b", a, re.IGNORECASE) for i, a in stage)
    ]
    assert runtime_stages, "Dockerfile.app 缺少 AS runtime 运行阶段"
    return runtime_stages[-1]


def _runtime_run_scripts() -> list[str]:
    return [
        args
        for instruction, args in _runtime_stage_instructions()
        if instruction == "RUN"
    ]


def test_app_runtime_stage_creates_fixed_non_root_user() -> None:
    """runtime 阶段必须以固定 UID/GID 创建非 root 系统用户/组。"""

    run_scripts = _runtime_run_scripts()
    groupadd_line = next((script for script in run_scripts if "groupadd" in script), None)
    useradd_line = next((script for script in run_scripts if "useradd" in script), None)

    assert groupadd_line, "runtime 阶段必须用 groupadd 创建运行组"
    assert re.search(rf"--gid\s+{APP_RUN_GID}\b", groupadd_line), groupadd_line

    assert useradd_line, "runtime 阶段必须用 useradd 创建运行用户"
    assert re.search(rf"--uid\s+{APP_RUN_UID}\b", useradd_line), useradd_line
    assert re.search(rf"--gid\s+{APP_RUN_GID}\b", useradd_line), useradd_line
    assert re.search(rf"\b{APP_RUN_USER_NAME}\b", useradd_line), useradd_line
    assert re.search(
        rf"--(?:home-dir|-d)\s+{re.escape(APP_RUN_HOME)}\b", useradd_line
    ), (
        f"useradd 必须把 {APP_RUN_USER_NAME} 的 passwd home 显式设为 {APP_RUN_HOME}，"
        f"实际：{useradd_line}"
    )


def test_app_runtime_passwd_home_matches_chowned_workdir() -> None:
    """mallapp 的 passwd home 必须正是非递归 chown 给它的 /app，且 USER 切换晚于该 chown。"""

    stage = _runtime_stage_instructions()
    run_scripts = "\n".join(_runtime_run_scripts())

    useradd_line = next((script for script in _runtime_run_scripts() if "useradd" in script), None)
    assert useradd_line, "runtime 阶段必须用 useradd 创建运行用户"
    passwd_home_match = re.search(
        rf"--(?:home-dir|-d)\s+(/\S+)", useradd_line
    )
    assert passwd_home_match, f"useradd 必须显式声明 passwd home，实际：{useradd_line}"
    passwd_home = passwd_home_match.group(1)
    assert passwd_home == APP_RUN_HOME, (
        f"{APP_RUN_USER_NAME} 的 passwd home 必须是 {APP_RUN_HOME}，实际为 {passwd_home!r}"
    )

    # home 目录必须正是非递归 chown 给运行用户的目录
    chown_match = re.search(
        rf"chown\s+(?:{APP_RUN_USER_NAME}|{APP_RUN_UID})"
        rf"(?::(?:{APP_RUN_USER_NAME}|{APP_RUN_GID}))?\s+(/\S+)",
        run_scripts,
    )
    assert chown_match, "必须把 home 目录 chown 给非 root 用户"
    assert chown_match.group(1) == passwd_home, (
        f"chown 的目录 {chown_match.group(1)!r} 必须与 passwd home {passwd_home!r} 一致"
    )

    # USER 切换必须晚于 home 目录 chown，保证切换后 home 已可写
    user_indexes = [i for i, (ins, _) in enumerate(stage) if ins == "USER"]
    chown_indexes = [i for i, (ins, args) in enumerate(stage) if ins == "RUN" and "chown" in args]
    assert user_indexes and chown_indexes, "runtime 阶段缺少 USER 或 chown /app 指令"
    assert max(chown_indexes) < min(user_indexes), "USER 必须晚于 home 目录 chown"


def test_app_runtime_home_env_is_selected_by_build_arg_defaulting_to_root() -> None:
    """runtime 阶段必须用 ARG APP_HOME（默认 /root）驱动 ENV HOME，不得写死 /app。

    原 eclipse-temurin:17-jre root 运行镜像的 HOME 为 /root（docker run 实测
    BASE_HOME=/root、USER=0）。共享本 Dockerfile 的三个服务以 root 运行时必须保持
    HOME=/root，只有显式传入 APP_HOME=/app 的 Agent 镜像才让 HOME 等于其 passwd home。
    """

    stage = _runtime_stage_instructions()

    arg_defaults = {
        args.split("=", 1)[0].strip(): args.split("=", 1)[1].strip()
        for instruction, args in stage
        if instruction == "ARG" and "=" in args
    }
    assert APP_HOME_ARG in arg_defaults, (
        f"runtime 阶段必须声明带默认值的 ARG {APP_HOME_ARG}，否则无法按服务选择 HOME"
    )
    assert arg_defaults[APP_HOME_ARG] == APP_HOME_DEFAULT, (
        f"{APP_HOME_ARG} 默认必须是 {APP_HOME_DEFAULT}（root 基础镜像的 HOME），"
        f"实际为 {arg_defaults[APP_HOME_ARG]!r}"
    )

    home_envs = [
        args
        for instruction, args in stage
        if instruction == "ENV" and args.split("=", 1)[0].strip() == "HOME"
    ]
    assert home_envs, "runtime 阶段必须显式设置 ENV HOME"
    home_env = home_envs[-1]
    assert "=" in home_env, f"ENV HOME 必须带取值，实际为 {home_env!r}"
    home_value = home_env.split("=", 1)[1].strip()
    assert home_value == f"${{{APP_HOME_ARG}}}", (
        f"ENV HOME 必须引用可选 ARG ${{{APP_HOME_ARG}}}（默认 {APP_HOME_DEFAULT}），"
        f"不得写死目录，实际为 {home_value!r}"
    )


def test_app_runtime_user_is_selected_by_build_arg_defaulting_to_root() -> None:
    """runtime 阶段最终 USER 必须引用可选 ARG，且该 ARG 默认 root。

    共享镜像的四个服务默认运行身份保持 root（与迁移前一致）；只有显式传入
    mallapp 的 Agent 镜像才会以非 root 运行。本用例保护「默认 root + 可选」这一
    结构契约，避免在未经容器验证时改变 mall-admin / mall-search / mall-portal 的运行身份。
    """

    stage = _runtime_stage_instructions()

    arg_defaults = {
        args.split("=", 1)[0].strip(): args.split("=", 1)[1].strip()
        for instruction, args in stage
        if instruction == "ARG" and "=" in args
    }
    assert APP_RUN_USER_ARG in arg_defaults, (
        f"runtime 阶段必须声明带默认值的 ARG {APP_RUN_USER_ARG}，否则无法按服务选择运行用户"
    )
    assert arg_defaults[APP_RUN_USER_ARG] == APP_RUN_USER_DEFAULT, (
        f"{APP_RUN_USER_ARG} 默认必须是 {APP_RUN_USER_DEFAULT}，"
        f"实际为 {arg_defaults[APP_RUN_USER_ARG]!r}"
    )

    users = [
        args for instruction, args in stage if instruction == "USER"
    ]

    assert users, "runtime 阶段必须用 USER 切换运行用户，否则无法按服务选择运行身份"
    final_user = users[-1]
    assert final_user == f"${{{APP_RUN_USER_ARG}}}", (
        f"最终 USER 必须引用可选 ARG ${{{APP_RUN_USER_ARG}}}，实际为 {final_user!r}"
    )


def test_app_runtime_switches_user_only_after_privileged_steps() -> None:
    """apt-get 安装与用户创建必须在 USER 之前完成，且 USER 必须先于 ENTRYPOINT。"""

    stage = _runtime_stage_instructions()
    user_indexes = [index for index, (instruction, _) in enumerate(stage) if instruction == "USER"]
    entrypoint_indexes = [
        index for index, (instruction, _) in enumerate(stage) if instruction == "ENTRYPOINT"
    ]

    assert user_indexes, "runtime 阶段缺少 USER 指令"
    assert entrypoint_indexes, "runtime 阶段缺少 ENTRYPOINT 指令"
    assert max(user_indexes) < min(entrypoint_indexes), "USER 必须在 ENTRYPOINT 之前生效"

    privileged = [
        index
        for index, (instruction, args) in enumerate(stage)
        if instruction == "RUN" and ("apt-get" in args or "useradd" in args)
    ]
    assert privileged, "runtime 阶段必须保留 apt-get 安装与用户创建的 RUN"
    assert max(privileged) < min(user_indexes), (
        "USER 必须晚于 apt-get 安装与用户创建，避免非 root 无法安装软件包或创建用户"
    )


def test_app_runtime_jar_is_owned_by_non_root_user() -> None:
    """app.jar 必须显式 --chown 给非 root 用户，保证该用户可读。"""

    copies = [
        args
        for instruction, args in _dockerfile_instructions(
            APP_DOCKERFILE_PATH.read_text(encoding="utf-8")
        )
        if instruction == "COPY" and "/app/app.jar" in args
    ]
    assert copies, "必须把模块 jar 复制为 /app/app.jar"
    jar_copy = copies[-1]

    assert re.search(
        rf"--chown=(?:{APP_RUN_USER_NAME}|{APP_RUN_UID}):(?:{APP_RUN_USER_NAME}|{APP_RUN_GID})\b",
        jar_copy,
    ), f"app.jar 必须 --chown 给非 root 用户，实际 COPY 指令为：{jar_copy}"


def test_app_runtime_makes_workdir_writable_without_recursive_chown() -> None:
    """/app 目录本体需赋权给非 root 用户（JVM 需在 CWD 写 hs_err/replay），禁止递归 chown。"""

    content = APP_DOCKERFILE_PATH.read_text(encoding="utf-8")
    run_scripts = "\n".join(_runtime_run_scripts())

    assert re.search(
        rf"chown\s+(?:{APP_RUN_USER_NAME}|{APP_RUN_UID})"
        rf"(?::(?:{APP_RUN_USER_NAME}|{APP_RUN_GID}))?\s+/app(?:\s|$)",
        run_scripts,
    ), "必须把 /app 目录本体 chown 给非 root 用户，保证 JVM 能在工作目录写 hs_err/replay 文件"

    for forbidden in ("chown -R", "chown --recursive", "chmod -R", "chmod --recursive"):
        assert forbidden not in content, f"不得对广泛路径递归 chown/chmod：{forbidden}"


def test_app_runtime_keeps_tmp_default_writable() -> None:
    """不得修改 /tmp 权限：Tomcat multipart 与 logback 日志依赖基础镜像的 1777。"""

    content = APP_DOCKERFILE_PATH.read_text(encoding="utf-8")

    assert not re.search(
        r"\b(?:chmod|chown)\b[^\n]*\s/tmp(?:\s|$)", content
    ), "不得 chmod/chown /tmp，应保留基础镜像默认可写"
    assert not re.search(
        r"\b(?:chmod|chown)\b[^\n]*(?:-R|--recursive)\s+/tmp\b", content
    ), "不得递归修改 /tmp 权限"


def test_app_runtime_preserves_curl_entrypoint_and_port() -> None:
    """切换到非 root 后仍保留 curl 安装、Java ENTRYPOINT 与端口声明。"""

    stage = _runtime_stage_instructions()
    run_scripts = "\n".join(_runtime_run_scripts())

    assert "apt-get install" in run_scripts and "curl" in run_scripts, (
        "runtime 阶段必须继续通过 apt-get 安装 curl 供 healthcheck 使用"
    )
    assert any(
        instruction == "EXPOSE" and args == "${APP_PORT}" for instruction, args in stage
    ), "runtime 阶段必须保留 EXPOSE ${APP_PORT}"
    assert any(
        instruction == "ENTRYPOINT" and "/app/app.jar" in args for instruction, args in stage
    ), "runtime 阶段必须保留 java -jar /app/app.jar 的 ENTRYPOINT"


def test_all_four_java_services_reuse_app_dockerfile(compose: dict) -> None:
    """mall-admin / mall-search / mall-portal / mall-shopping-agent 必须都复用 Dockerfile.app。"""

    for name in SHARED_APP_DOCKERFILE_SERVICES:
        service = (compose.get("services") or {}).get(name)
        assert service, f"docker-compose.yml 缺少服务 {name}"

        build = service.get("build") or {}
        assert build.get("context") in {".", "./"}, (
            f"{name} 构建上下文必须是仓库根，实际为 {build.get('context')!r}"
        )
        assert build.get("dockerfile") == "document/docker/Dockerfile.app", (
            f"{name} 必须复用 document/docker/Dockerfile.app，实际为 {build.get('dockerfile')!r}"
        )


def test_agent_build_arg_selects_mallapp_run_user(agent_service: dict) -> None:
    """只有 mall-shopping-agent 的 build.args 传入 ARG=mallapp，使镜像以 10001 运行。"""

    build = agent_service.get("build") or {}
    args = build.get("args") or {}

    assert args.get(APP_RUN_USER_ARG) == APP_RUN_USER_AGENT_VALUE, (
        f"Agent 必须通过 build.args {APP_RUN_USER_ARG}={APP_RUN_USER_AGENT_VALUE} 选择非 root 运行用户，"
        f"实际为 {args.get(APP_RUN_USER_ARG)!r}"
    )

    # mallapp 必须正是 runtime 阶段 useradd 创建的 UID/GID 10001:10001 用户
    useradd_line = next(
        (script for script in _runtime_run_scripts() if "useradd" in script), None
    )
    assert useradd_line, "runtime 阶段必须用 useradd 创建运行用户"
    assert re.search(rf"--uid\s+{APP_RUN_UID}\b", useradd_line), useradd_line
    assert re.search(rf"--gid\s+{APP_RUN_GID}\b", useradd_line), useradd_line


def test_agent_build_arg_selects_app_home_matching_passwd_home(agent_service: dict) -> None:
    """Agent 必须通过 build.args 把 APP_HOME 选为 /app，与 mallapp 的 passwd home 一致。"""

    build_args = (agent_service.get("build") or {}).get("args") or {}
    assert build_args.get(APP_HOME_ARG) == APP_HOME_AGENT_VALUE, (
        f"Agent 必须通过 build.args {APP_HOME_ARG}={APP_HOME_AGENT_VALUE} 选择 HOME，"
        f"实际为 {build_args.get(APP_HOME_ARG)!r}"
    )

    useradd_line = next(
        (script for script in _runtime_run_scripts() if "useradd" in script), None
    )
    assert useradd_line, "runtime 阶段必须用 useradd 创建运行用户"
    assert re.search(rf"--(?:home-dir|-d)\s+{re.escape(APP_RUN_HOME)}\b", useradd_line), (
        f"mallapp 的 passwd home 必须是 {APP_RUN_HOME}，才能与 Agent 传入的 "
        f"{APP_HOME_ARG}={APP_HOME_AGENT_VALUE} 一致"
    )


def test_agent_compose_user_override_matches_selected_run_user(agent_service: dict) -> None:
    """agent 在 Compose 显式 user 必须与 build.args 选择的镜像运行用户完全一致。"""

    build_args = (agent_service.get("build") or {}).get("args") or {}
    assert build_args.get(APP_RUN_USER_ARG) == APP_RUN_USER_AGENT_VALUE, (
        f"Agent 镜像的 Config.User 必须由 build.args 选为 {APP_RUN_USER_AGENT_VALUE}"
    )
    assert agent_service.get("user") == APP_RUN_USER_SPEC, (
        f"Compose 显式 user 必须与镜像选择一致（{APP_RUN_USER_SPEC}），"
        f"实际为 {agent_service.get('user')!r}"
    )


# --------------------------------------------------------------------------- #
# Nginx /agent-api/ 访问日志脱敏：游客会话凭据不得落入 mall-access.log
# --------------------------------------------------------------------------- #
# 移动端用 GET /agent-api/agent/session/{sessionId} 恢复游客会话，sessionId 即会话凭据
# （Redis 键 mall:agent:session:guest:<sessionId>）。server 级 access_log 未指定格式时使用
# Nginx 内置 combined，会把完整请求行（URL 含凭据与查询串）写入 mall-access.log。
#
# 以下用例解析 /agent-api/ 「实际生效」的访问日志格式：location 未声明 access_log 时回退
# server 级，再按引用的 log_format 取变量集合断言，不硬编码生产配置，也不模拟渲染行为。

AGENT_API_LOCATION = "/agent-api/"
# Nginx 未显式指定 log_format 时 access_log 使用的内置 combined 格式
BUILTIN_COMBINED_FORMAT = (
    '$remote_addr - $remote_user [$time_local] "$request" '
    "$status $body_bytes_sent "
    '"$http_referer" "$http_user_agent"'
)
# 会记录请求目标 / 查询串 / 凭据 / 客户端 IP 的变量：脱敏格式中必须全部缺席
SENSITIVE_LOG_VARIABLES = frozenset(
    {
        "request", "request_uri", "uri", "document_uri",
        "args", "query_string",
        "http_authorization", "http_cookie",
        "remote_addr", "http_x_real_ip",
    }
)
# 排障必需：脱敏后仍须保留的诊断变量
REQUIRED_LOG_VARIABLES = frozenset({"status", "request_method", "request_time"})

_NGINX_VARIABLE_RE = re.compile(r"\$\{([A-Za-z0-9_]+)\}|\$([A-Za-z0-9_]+)")
_NGINX_LOG_FORMAT_RE = re.compile(r"log_format\s+([A-Za-z0-9_]+)\s+((?:[^;])*);", re.DOTALL)
_ACCESS_LOG_RE = re.compile(r"access_log\s+([^;]+);")


def _strip_nginx_comments(text: str) -> str:
    """去掉 Nginx 文本中的 ``#`` 注释（配置字符串内不含 ``#``）。"""

    return "\n".join(re.sub(r"#.*$", "", line) for line in text.splitlines())


def _nginx_conf_without_comments() -> str:
    """读取 Nginx 配置并去掉 ``#`` 注释，便于结构性解析（配置字符串内不含 ``#``）。"""

    return _strip_nginx_comments(NGINX_CONF_PATH.read_text(encoding="utf-8"))


def _nginx_block(content: str, header: str) -> str:
    """返回 ``header`` 指令后配对花括号块的内部文本。"""

    match = re.search(header + r"\s*\{", content)
    assert match, f"Nginx 配置缺少匹配块：{header}"
    open_index = content.index("{", match.start())
    depth = 0
    for index in range(open_index, len(content)):
        if content[index] == "{":
            depth += 1
        elif content[index] == "}":
            depth -= 1
            if depth == 0:
                return content[open_index + 1 : index]
    raise AssertionError("Nginx 配置花括号不平衡")


def _nginx_variables(text: str) -> set[str]:
    """取出文本中出现的完整 Nginx 变量名（避免 $request 误匹配 $request_time）。"""

    return {braced or plain for braced, plain in _NGINX_VARIABLE_RE.findall(text)}


def _effective_agent_access_log_format() -> str:
    """/agent-api/ 实际生效的日志格式：location 覆盖优先，否则回退 server 级。"""

    content = _nginx_conf_without_comments()
    location = _nginx_block(content, r"location\s+" + re.escape(AGENT_API_LOCATION))
    directive = _ACCESS_LOG_RE.search(location)
    if directive is None:
        directive = _ACCESS_LOG_RE.search(_nginx_block(content, r"\bserver"))

    assert directive, "server 级缺少 access_log 指令"
    tokens = directive.group(1).split()
    assert tokens[0] != "off", "/agent-api/ 不得用 access_log off 关闭日志（丢失诊断信息）"

    name = tokens[1] if len(tokens) > 1 else "combined"
    if name == "combined":
        return BUILTIN_COMBINED_FORMAT

    formats = dict(_NGINX_LOG_FORMAT_RE.findall(content))
    assert name in formats, f"/agent-api/ 引用了未定义的 log_format：{name!r}"
    return formats[name]


def _server_access_log_directive() -> str:
    directive = _ACCESS_LOG_RE.search(_nginx_block(_nginx_conf_without_comments(), r"\bserver"))
    assert directive, "server 级缺少 access_log 指令"
    return directive.group(1).strip()


def test_nginx_agent_api_access_log_format_redacts_sensitive_variables() -> None:
    """/agent-api/ 实际生效的日志格式不得含 URL / 查询串 / 凭据 / 客户端 IP 变量。

    旧配置在 server 级沿用内置 combined，会把完整请求行（含游客会话凭据）写入
    mall-access.log；本用例是针对该问题的最小回归断言。
    """

    variables = _nginx_variables(_effective_agent_access_log_format())
    leaked = variables & SENSITIVE_LOG_VARIABLES
    assert leaked == set(), f"/agent-api/ 访问日志仍会记录敏感变量：{sorted(leaked)}"


def test_nginx_agent_api_access_log_format_keeps_diagnostic_variables() -> None:
    """脱敏不得牺牲可诊断性：状态码、请求方法与请求耗时必须保留。"""

    variables = _nginx_variables(_effective_agent_access_log_format())
    missing = REQUIRED_LOG_VARIABLES - variables
    assert missing == set(), f"/agent-api/ 访问日志缺少诊断变量：{sorted(missing)}"


def test_nginx_server_level_access_log_format_unchanged() -> None:
    """server 级日志仍写同一文件且不指定格式（沿用内置 combined），语义未被改动。"""

    assert _server_access_log_directive() == "/var/log/nginx/mall-access.log", (
        "server 级 access_log 不得改动为自定义格式或被关闭"
    )


# --------------------------------------------------------------------------- #
# Nginx /agent-api/ 最小加固：读超时预算、请求体上限与隔离错误日志
# --------------------------------------------------------------------------- #
# 三项已获批准的加固（只作用于 /agent-api/ location）：
#   1) proxy_read_timeout 40s -> 60s：覆盖「门户身份解析 10s + Agent 请求 35s」服务端总预算；
#   2) client_max_body_size 32k：允许 1000 Unicode code points 的 JSON 字符串合法编码
#      （最坏 UTF-8 4 字节/码点，32k 有充足余量），同时收紧该路由的请求体上限；
#   3) location 级 error_log 丢弃：Nginx error details 会记录含 sessionId 的请求 URI，
#      把该路由的错误日志隔离到 /dev/null，避免原始错误日志落盘凭据；
#      access_log 继续保留脱敏的状态 / 方法 / 耗时。
#
# 以下用例解析 location 内**实际生效**的指令（location 覆盖优先，否则回退 server 级），
# 断言只有 /agent-api/ 被加固，其余 location 仍继承 server 级 20m / 原错误日志设置。

# server 级请求体上限（其余 location 的继承值）
NGINX_SERVER_CLIENT_MAX_BODY_SIZE = "20m"
# /agent-api/ 专用请求体上限
NGINX_AGENT_CLIENT_MAX_BODY_SIZE = "32k"
# /agent-api/ 隔离错误日志的落点：丢弃该路由的 Nginx error details
NGINX_AGENT_ERROR_LOG_SINK = "/dev/null"
# server 级错误日志指令（其它 location 的继承值，必须逐字保持）
NGINX_SERVER_ERROR_LOG_DIRECTIVE = "/var/log/nginx/mall-error.log warn"
# 其它代理 location 保持原 120s 读超时
NGINX_INHERITED_PROXY_READ_TIMEOUT = "120s"
# 必须继承 server 级设置、不得被加固的其它 location
NGINX_UNTIGHTENED_LOCATIONS = ("/", "/h5/", "/static/", "/admin-api/", "/portal-api/", "/es-api/")
NGINX_OTHER_PROXY_LOCATIONS = ("/admin-api/", "/portal-api/", "/es-api/")

_NGINX_DIRECTIVE_RE = re.compile(r"([a-z_]+)\s+([^;]+);")


def _nginx_server_block() -> str:
    """返回 ``server { ... }`` 块的内部文本（已去注释）。"""

    return _nginx_block(_nginx_conf_without_comments(), r"\bserver")


def _matching_brace(text: str, open_index: int) -> int:
    """返回 ``text[open_index]`` 处 ``{`` 所配对的 ``}`` 下标（按花括号深度配对）。"""

    depth = 0
    for index in range(open_index, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError("Nginx 块花括号未闭合，无法定位配对的 '}'")


def _nginx_direct_statements(block: str) -> list[tuple[str, str]]:
    """按花括号深度解析 ``block`` 内部文本，只产出**本层（深度 0）**语句。

    每个元素为 ``(文本, 子块体)``：

    - 指令语句：子块体为 ``""``，文本为含结尾 ``;`` 的指令；
    - 子块：文本为块头（如 ``location /agent-api/``），子块体为其内部文本。

    子块整体被跳过，因此更深层级的指令/子块**不会**出现在本层；父块不会把嵌套内容
    当作自身直接值，server 级提取也不会混入任何 location 内容。空白的尾部文本被忽略。
    """

    statements: list[tuple[str, str]] = []
    statement_start = 0
    index = 0
    length = len(block)
    while index < length:
        char = block[index]
        if char == ";":
            statements.append((block[statement_start : index + 1], ""))
            statement_start = index + 1
        elif char == "{":
            header = block[statement_start:index].strip()
            close = _matching_brace(block, index)
            statements.append((header, block[index + 1 : close]))
            index = close + 1
            statement_start = index
            continue
        index += 1
    tail = block[statement_start:]
    if tail.strip():
        statements.append((tail, ""))
    return statements


def _nginx_location_blocks(server: str | None = None) -> dict[str, str]:
    """返回 server 内**直接** location 子块的内部文本映射（按花括号深度解析）。

    只取 server 的直接子块；嵌套在某个 location 内部的 location 不会出现在这里。
    ``server`` 省略时读取真实配置文件，便于在测试中传入合成片段。
    """

    text = _nginx_server_block() if server is None else server
    blocks: dict[str, str] = {}
    for header, child in _nginx_direct_statements(text):
        if not child:
            continue
        tokens = header.split()
        if len(tokens) == 2 and tokens[0] == "location":
            blocks[tokens[1]] = child
    assert blocks, "server 块内未解析到任何 location"
    return blocks


def _effective_directives(block: str) -> dict[str, str]:
    """把块文本解析为 {指令名: 取值}（**只取本层直接指令**，跳过所有子块）。

    同名指令后者覆盖前者；子块内的指令不参与本层，避免父块被嵌套子块污染。
    """

    directives: dict[str, str] = {}
    for statement, child in _nginx_direct_statements(block):
        if child:
            continue
        for name, value in _NGINX_DIRECTIVE_RE.findall(statement):
            directives[name] = value.strip()
    return directives


def _agent_location_directives() -> dict[str, str]:
    blocks = _nginx_location_blocks()
    assert AGENT_API_LOCATION in blocks, f"Nginx 配置缺少 location {AGENT_API_LOCATION}"
    return _effective_directives(blocks[AGENT_API_LOCATION])


def _strip_location_blocks(block: str) -> str:
    """只保留本层（深度 0）指令文本，按花括号深度剔除**所有**直接子块。"""

    return "".join(
        statement for statement, child in _nginx_direct_statements(block) if not child
    )


def _server_level_directives(server: str | None = None) -> dict[str, str]:
    """server 级直接指令（排除所有 location 内容，避免被子块同名指令覆盖）。

    ``server`` 省略时读取真实配置文件，便于在测试中传入合成片段。
    """

    text = _nginx_server_block() if server is None else server
    return _effective_directives(_strip_location_blocks(text))


def test_nginx_agent_location_caps_request_body_at_32k_only() -> None:
    """/agent-api/ 生效的 client_max_body_size 必须是 32k，其它 location 仍继承 server 级 20m。"""

    agent = _agent_location_directives()
    assert agent.get("client_max_body_size") == NGINX_AGENT_CLIENT_MAX_BODY_SIZE, (
        f"{AGENT_API_LOCATION} 必须设置 client_max_body_size {NGINX_AGENT_CLIENT_MAX_BODY_SIZE}，"
        f"实际为 {agent.get('client_max_body_size')!r}"
    )

    server = _server_level_directives()
    assert server.get("client_max_body_size") == NGINX_SERVER_CLIENT_MAX_BODY_SIZE, (
        f"server 级 client_max_body_size 必须保持 {NGINX_SERVER_CLIENT_MAX_BODY_SIZE}"
    )

    blocks = _nginx_location_blocks()
    for name in NGINX_UNTIGHTENED_LOCATIONS:
        assert "client_max_body_size" not in _effective_directives(blocks[name]), (
            f"只有 {AGENT_API_LOCATION} 允许收紧请求体上限，{name} 不得单独声明"
        )


def test_nginx_agent_location_read_timeout_covers_identity_plus_request_budget() -> None:
    """/agent-api/ 读超时必须是 60s，覆盖门户身份解析 10s + Agent 请求 35s，其它代理仍 120s。"""

    agent = _agent_location_directives()
    assert agent.get("proxy_read_timeout") == f"{NGINX_AGENT_READ_TIMEOUT_SECONDS}s", (
        f"{AGENT_API_LOCATION} 的 proxy_read_timeout 必须是 "
        f"{NGINX_AGENT_READ_TIMEOUT_SECONDS}s，实际为 {agent.get('proxy_read_timeout')!r}"
    )

    portal_budget = int(AGENT_COMPOSE_FIXED_VARIABLES["MALL_AGENT_PORTAL_TIMEOUT_SECONDS"])
    request_budget = int(AGENT_COMPOSE_FIXED_VARIABLES["MALL_AGENT_REQUEST_TIMEOUT_SECONDS"])
    assert NGINX_AGENT_READ_TIMEOUT_SECONDS >= portal_budget + request_budget, (
        "Nginx 读超时必须不小于「门户身份解析 + Agent 请求」的服务端总预算"
    )

    blocks = _nginx_location_blocks()
    for name in NGINX_OTHER_PROXY_LOCATIONS:
        assert (
            _effective_directives(blocks[name]).get("proxy_read_timeout")
            == NGINX_INHERITED_PROXY_READ_TIMEOUT
        ), f"{name} 的读超时必须保持 {NGINX_INHERITED_PROXY_READ_TIMEOUT}"


def test_nginx_agent_location_isolates_error_log_and_keeps_redacted_access_log() -> None:
    """/agent-api/ 必须隔离错误日志（/dev/null），server 级错误日志与脱敏 access_log 保持不变。"""

    agent = _agent_location_directives()

    assert "error_log" in agent, f"{AGENT_API_LOCATION} 必须声明 location 级 error_log"
    assert agent["error_log"].split()[0] == NGINX_AGENT_ERROR_LOG_SINK, (
        f"{AGENT_API_LOCATION} 的 error_log 必须隔离到 {NGINX_AGENT_ERROR_LOG_SINK}，"
        f"避免 error details 记录含 sessionId 的请求 URI，实际为 {agent['error_log']!r}"
    )

    # 隔离错误日志不得连带关闭访问日志：脱敏 access_log 仍必须保留
    assert "access_log" in agent, "隔离 error_log 不得移除 access_log"
    assert agent["access_log"].split()[0] != "off", "/agent-api/ 仍必须保留脱敏访问日志"

    server = _server_level_directives()
    assert server.get("error_log") == NGINX_SERVER_ERROR_LOG_DIRECTIVE, (
        "server 级 error_log 必须保持原路径与级别（其它 location 的继承值）"
    )

    blocks = _nginx_location_blocks()
    for name in NGINX_UNTIGHTENED_LOCATIONS:
        assert "error_log" not in _effective_directives(blocks[name]), (
            f"只有 {AGENT_API_LOCATION} 允许隔离错误日志，{name} 不得单独声明"
        )


# --------------------------------------------------------------------------- #
# Nginx 块解析：按花括号深度只取直接子块，禁止嵌套内容污染父级 / server 级
# --------------------------------------------------------------------------- #
# 审查意见：原实现用全局 regex 扫描 ``location\\s+\\S+\\s*\\{``，会把某个 location
# **内部**的嵌套 location 也当成父级的直接子块，并把嵌套块里的同名指令当成父级直接值。
# 以下用例用合成配置（含注释，交给 _strip_nginx_comments 处理）锁定深度解析语义。

_NESTED_LOCATION_FIXTURE = """
    # 顶层 server 指令 + 一个含嵌套 location 的 location
    server {
        client_max_body_size 20m;
        error_log /var/log/nginx/mall-error.log warn;
        location /agent-api/ {
            client_max_body_size 32k;
            error_log /dev/null;
            proxy_read_timeout 60s;
            location /health/ {
                proxy_read_timeout 999s;
                error_log /tmp/nested-health.log;
            }
        }
        location /portal-api/ {
            proxy_read_timeout 120s;
        }
    }
"""


def test_nginx_block_parser_keeps_nested_location_out_of_parent_directives() -> None:
    """父 location 不得把嵌套 location 的指令当成自身直接值（按花括号深度解析）。"""

    fixture = _strip_nginx_comments(_NESTED_LOCATION_FIXTURE)
    server_inner = _nginx_block(fixture, r"\bserver")

    blocks = _nginx_location_blocks(server_inner)
    assert set(blocks) == {AGENT_API_LOCATION, "/portal-api/"}, (
        "嵌套 location 不得被解析为 server 的直接子块"
    )

    agent = _effective_directives(blocks[AGENT_API_LOCATION])
    assert agent["client_max_body_size"] == "32k"
    assert agent["proxy_read_timeout"] == "60s"
    assert agent["error_log"] == "/dev/null", (
        "父 location 必须保留自身 error_log，不得被嵌套 location 的 error_log 覆盖"
    )

    nested = _nginx_location_blocks(blocks[AGENT_API_LOCATION])
    assert set(nested) == {"/health/"}, "嵌套 location 必须作为父块自己的直接子块被解析"
    nested_directives = _effective_directives(nested["/health/"])
    assert nested_directives["proxy_read_timeout"] == "999s"
    assert nested_directives["error_log"] == "/tmp/nested-health.log"


def test_nginx_server_level_extraction_excludes_all_location_content() -> None:
    """server 级提取必须排除所有 location（含嵌套 location）内容。"""

    fixture = _strip_nginx_comments(_NESTED_LOCATION_FIXTURE)
    server_inner = _nginx_block(fixture, r"\bserver")

    stripped = _strip_location_blocks(server_inner)
    assert "location" not in stripped, "server 级文本不得残留任何 location 块"
    assert "{" not in stripped and "}" not in stripped, (
        "server 级文本不得残留子块花括号（说明嵌套块未被完整剔除）"
    )
    for leaked in ("32k", "/dev/null", "999s", "/tmp/nested-health.log", "120s"):
        assert leaked not in stripped, f"{leaked} 属于 location 内容，不得残留在 server 级文本中"

    directives = _server_level_directives(server_inner)
    assert directives["client_max_body_size"] == "20m"
    assert directives["error_log"] == "/var/log/nginx/mall-error.log warn"
