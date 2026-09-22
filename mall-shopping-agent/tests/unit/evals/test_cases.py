from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from support.eval_harness import CASES_PATH, load_cases, run_all_cases

REPO_ROOT = Path(__file__).resolve().parents[3]
RUNNER = REPO_ROOT / "evals" / "run_evals.py"

EXPECTED_CATEGORIES = {
    "keyword_search",
    "filter_by_brand_or_category",
    "budget_sorting",
    "compare_products",
    "sku_stock",
    "guest_coupons",
    "member_coupons",
    "missing_product",
    "transaction_refusal",
    "prompt_injection",
}


def test_cases_file_covers_ten_categories_with_unique_ids() -> None:
    cases = load_cases()

    assert len(cases) == 10
    assert {case.category for case in cases} == EXPECTED_CATEGORIES
    assert len({case.id for case in cases}) == len(cases)


def test_each_case_declares_the_full_assertion_set() -> None:
    for case in load_cases():
        assert case.allowed_tools or case.forbidden_tools, case.id
        assert case.max_tool_calls >= 0, case.id
        assert case.product_card_count >= 0, case.id
        assert isinstance(case.requires_login, bool), case.id
        assert case.required_facts, case.id
        assert not set(case.allowed_tools) & set(case.forbidden_tools), case.id


def test_write_requests_never_allow_any_tool() -> None:
    refusal = next(case for case in load_cases() if case.category == "transaction_refusal")

    assert refusal.allowed_tools == []
    assert refusal.max_tool_calls == 0
    assert refusal.product_card_count == 0


def test_unknown_field_in_case_fails_schema_validation(tmp_path: Path) -> None:
    raw = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    raw["cases"][0]["unexpectedField"] = "boom"
    broken = tmp_path / "cases.json"
    broken.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(ValueError):
        load_cases(broken)


def test_missing_required_field_fails_schema_validation(tmp_path: Path) -> None:
    raw = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    del raw["cases"][0]["maxToolCalls"]
    broken = tmp_path / "cases.json"
    broken.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(ValueError):
        load_cases(broken)


def test_unknown_tool_name_in_cases_fails_validation(tmp_path: Path) -> None:
    raw = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    raw["cases"][0]["allowedTools"] = ["deleteProduct"]
    broken = tmp_path / "cases.json"
    broken.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(ValueError):
        load_cases(broken)


async def test_all_ten_stub_cases_pass() -> None:
    report = await run_all_cases()

    assert report.total == 10
    assert report.failures == []
    assert report.passed == 10
    assert report.unknown_tools == []


def test_stub_runner_cli_exits_zero_and_does_not_print_secrets() -> None:
    result = subprocess.run(
        [sys.executable, str(RUNNER), "--mode", "stub"],
        capture_output=True,
        text=True,
        cwd=str(REPO_ROOT),
        check=False,
    )

    output = result.stdout + result.stderr
    assert result.returncode == 0, output
    assert "10/10" in output
    assert "sk-" not in output
    assert "Bearer" not in output


def test_live_mode_without_credentials_is_not_reported_as_passed() -> None:
    env = {key: value for key, value in os.environ.items() if not key.startswith("MALL_AGENT_")}

    result = subprocess.run(
        [sys.executable, str(RUNNER), "--mode", "live"],
        capture_output=True,
        text=True,
        cwd=str(REPO_ROOT),
        env=env,
        check=False,
    )

    output = result.stdout + result.stderr
    assert result.returncode != 0
    assert "未" in output
    assert "通过率" not in output
