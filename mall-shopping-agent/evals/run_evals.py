"""商品导购智能体离线评测入口。

用法::

    py -3.11 evals/run_evals.py --mode stub
    py -3.11 evals/run_evals.py --mode live

``stub`` 模式完全离线，不需要 Redis、真实模型或外部网络。
``live`` 模式只在本地显式配置真实模型凭据后运行，且只输出通过率、工具次数和耗时。
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
for _path in (ROOT / "src", ROOT / "tests"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from mall_shopping_agent.agent.orchestrator import ShoppingAgentOrchestrator  # noqa: E402
from mall_shopping_agent.agent.types import AgentLimits, AgentTurnRequest  # noqa: E402
from mall_shopping_agent.config import Settings  # noqa: E402
from mall_shopping_agent.model.openai_compatible import OpenAICompatibleClient  # noqa: E402
from mall_shopping_agent.session.identity import Identity  # noqa: E402
from mall_shopping_agent.session.repository import SessionSnapshot  # noqa: E402
from mall_shopping_agent.storefront.mall_portal import MallPortalBackend  # noqa: E402
from mall_shopping_agent.tools import create_tool_registry  # noqa: E402
from support.eval_harness import (  # noqa: E402
    DEFAULT_MEMBER_ID,
    MEMBER_TOKEN,
    SESSION_ID,
    EvalCase,
    EvalReport,
    load_cases,
    run_all_cases,
)


def _print_report(report: EvalReport, *, mode: str) -> None:
    print(f"模式：{mode}")
    print(f"通过：{report.passed}/{report.total}")
    print(f"耗时：{report.duration_seconds:.2f}s")
    print(f"工具调用统计：{report.tool_counts}")
    if report.unknown_tools:
        print(f"未注册工具：{report.unknown_tools}")
    for failure in report.failures:
        print(f"失败：{failure}")


async def _run_live(cases: list[EvalCase], settings: Settings) -> EvalReport:
    backend = MallPortalBackend(
        base_url=settings.portal_base_url,
        timeout_seconds=settings.portal_timeout_seconds,
    )
    model = OpenAICompatibleClient(
        base_url=settings.openai_base_url,
        api_key=settings.openai_api_key,
        model=settings.openai_model,
        timeout_seconds=settings.openai_timeout_seconds,
    )
    registry = create_tool_registry()
    orchestrator = ShoppingAgentOrchestrator(
        model=model,
        registry=registry,
        backend=backend,
        limits=AgentLimits(),
    )

    outcomes = []
    tool_counts: dict[str, int] = {}
    failures: list[str] = []
    unknown: list[str] = []
    started = time.perf_counter()

    for case in cases:
        identity = (
            Identity.member(DEFAULT_MEMBER_ID, SESSION_ID)
            if case.identity == "member"
            else Identity.guest(SESSION_ID)
        )
        try:
            result = await orchestrator.run(
                AgentTurnRequest(
                    message=case.message,
                    identity=identity,
                    session=SessionSnapshot(),
                    authorization=MEMBER_TOKEN if case.identity == "member" else None,
                )
            )
        except Exception as exc:
            # 只记录异常类型，不输出任何异常正文
            failures.append(f"{case.id}: 运行失败（{type(exc).__name__}）")
            continue

        names = [record.name for record in result.tool_calls]
        for name in names:
            tool_counts[name] = tool_counts.get(name, 0) + 1
        unknown.extend(name for name in names if name not in registry.names)

        problems: list[str] = []
        for name in names:
            if name not in case.allowed_tools:
                problems.append(f"调用了未允许的工具：{name}")
        for name in case.forbidden_tools:
            if name in names:
                problems.append(f"调用了禁止的工具：{name}")
        if len(names) > case.max_tool_calls:
            problems.append(f"工具调用次数 {len(names)} 超过上限 {case.max_tool_calls}")
        if result.requires_login != case.requires_login:
            problems.append("requiresLogin 与预期不一致")
        if len(result.products) != case.product_card_count:
            problems.append("商品卡片数量与预期不一致")

        if problems:
            failures.append(f"{case.id}: {'; '.join(problems)}")
        else:
            outcomes.append(case.id)

    await model.aclose()
    await backend.aclose()

    return EvalReport(
        total=len(cases),
        passed=len(outcomes),
        failures=failures,
        tool_counts=tool_counts,
        unknown_tools=sorted(set(unknown)),
        duration_seconds=time.perf_counter() - started,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="商品导购智能体离线评测")
    parser.add_argument("--mode", choices=("stub", "live"), default="stub")
    args = parser.parse_args(argv)

    cases = load_cases()

    if args.mode == "stub":
        report = asyncio.run(run_all_cases(cases))
        _print_report(report, mode="stub")
        return 0 if report.passed == report.total else 1

    settings = Settings()
    if settings.model_mode == "stub" or not settings.model_available:
        print("未配置真实模型凭据（MALL_AGENT_OPENAI_API_KEY），live 模式未运行。")
        print("请在本地 .env 中安全配置凭据后重试；Stub 模式不受影响。")
        return 2

    report = asyncio.run(_run_live(cases, settings))
    print(f"模型服务：{settings.openai_base_url}")
    print(f"模型名：{settings.openai_model}")
    _print_report(report, mode="live")
    return 0 if report.passed == report.total else 1


if __name__ == "__main__":
    raise SystemExit(main())
