"""Compose / Nginx / Dockerfile 静态结构断言。

这些用例不启动容器、不访问网络；动态渲染校验由 ``docker compose config``
命令在验收步骤中单独执行。其中环境校验脚本部分会以子进程方式实际运行脚本，
用临时 env 文件验证退出码与输出脱敏。
"""

from __future__ import annotations

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
    assert "proxy_read_timeout    40s;" in content


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
    ["ftp://model.local.test/v1", "https://model.local.test", "model.local.test/v1"],
)
def test_invalid_base_url_fails(env_check_runner: str, tmp_path: Path, base_url: str) -> None:
    env_file = _write_env_file(tmp_path, "bad-url.env", _openai_env(base_url=base_url))

    assert _run_env_check(env_check_runner, env_file).returncode == 1


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
