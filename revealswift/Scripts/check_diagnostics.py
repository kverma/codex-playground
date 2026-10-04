#!/usr/bin/env python3
import json
import sys
from pathlib import Path


def fail(message: str):
    raise AssertionError(message)


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "diagnostic-artifacts")
    data = json.loads((root / "diagnostics.json").read_text())
    if data.get("schemaVersion") != 1:
        fail(f"unexpected diagnostics schema: {data.get('schemaVersion')}")

    findings = data.get("findings", [])
    if len(findings) < 2:
        fail(f"expected at least two diagnostic findings, got {len(findings)}")

    ids = [finding["id"] for finding in findings]
    if len(ids) != len(set(ids)):
        fail(f"diagnostic ids must be unique: {ids}")

    tiny = next((f for f in findings if f.get("rule") == "theme.fontTooSmall"), None)
    if tiny is None:
        fail("missing theme.fontTooSmall finding")
    if tiny.get("slideID") != "diagnostic-slide":
        fail(f"wrong slide identity: {tiny}")
    if "#tiny-copy" not in [t.get("selector") for t in tiny.get("targets", [])]:
        fail(f"tiny-copy selector missing: {tiny}")
    if "This exact tiny text should appear in agent feedback." not in tiny.get("agentFeedback", ""):
        fail(f"exact target text missing from feedback: {tiny}")
    if not tiny.get("suggestedFix"):
        fail(f"suggested fix missing: {tiny}")

    for finding in findings:
        if not finding.get("id", "").startswith("diag-s"):
            fail(f"unstable diagnostic id format: {finding}")
        if not finding.get("agentFeedback"):
            fail(f"agent feedback missing: {finding}")
        if not finding.get("suggestedFix"):
            fail(f"suggested fix missing: {finding}")

        screenshot = finding.get("screenshot")
        if screenshot:
            screenshot_path = root / screenshot
            if not screenshot_path.exists() or screenshot_path.stat().st_size == 0:
                fail(f"diagnostic screenshot missing/empty: {screenshot_path}")

        for target in finding.get("targets", []):
            bounds = target.get("bounds")
            if bounds and not (bounds.get("width", 0) > 0 and bounds.get("height", 0) > 0):
                fail(f"invalid target bounds: {target}")

    report = json.loads((root / "report.json").read_text())
    artifacts = report.get("artifacts", {})
    if artifacts.get("diagnostics") != "diagnostics.json":
        fail(f"diagnostics artifact missing from report: {artifacts}")
    if artifacts.get("diagnosticScreenshots") != "diagnostics/":
        fail(f"diagnostic screenshot directory missing from report: {artifacts}")

    print(f"RevealSwift diagnostics gate passed: {len(findings)} findings with agent feedback and annotated screenshots")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
