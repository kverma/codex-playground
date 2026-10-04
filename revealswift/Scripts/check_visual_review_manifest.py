#!/usr/bin/env python3
import json
import sys
from pathlib import Path


def fail(message: str):
    raise AssertionError(message)


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "diagnostic-artifacts")
    manifest_path = root / "review-manifest.json"
    prompt_path = root / "visual-review-prompt.md"
    diagnostics_path = root / "diagnostics.json"

    manifest = json.loads(manifest_path.read_text())
    diagnostics = json.loads(diagnostics_path.read_text())

    if manifest.get("schemaVersion") != 1:
        fail(f"unexpected manifest schema: {manifest.get('schemaVersion')}")
    if manifest.get("reviewerRole") != "independent-visual-reviewer":
        fail(f"unexpected reviewer role: {manifest.get('reviewerRole')}")
    if manifest.get("deterministicDiagnostics") != "diagnostics.json":
        fail("manifest must point to diagnostics.json")
    if not manifest.get("instructions") or not manifest.get("criteria"):
        fail("manifest must include reviewer instructions and criteria")

    contract = manifest.get("outputContract", {})
    if contract.get("outputFile") != "visual-review.json":
        fail(f"unexpected visual review output file: {contract}")
    if set(contract.get("allowedDecisions", [])) != {"pass", "needs_changes"}:
        fail(f"unexpected decisions: {contract}")
    if not contract.get("allowedCategories"):
        fail("visual review categories are empty")

    items = manifest.get("items", [])
    if not items:
        fail("manifest has no review items")
    ids = [item["id"] for item in items]
    if len(ids) != len(set(ids)):
        fail(f"duplicate review item ids: {ids}")

    overview = [item for item in items if item.get("kind") == "contact-sheet"]
    if len(overview) != 1:
        fail(f"expected exactly one contact-sheet item, got {len(overview)}")

    screenshot_files = sorted((root / "screenshots").glob("*.png"))
    slide_items = [item for item in items if item.get("kind") == "slide-state"]
    if len(slide_items) != len(screenshot_files):
        fail(f"slide-state queue mismatch: manifest={len(slide_items)} screenshots={len(screenshot_files)}")

    expected_diagnostic_images = {
        finding["screenshot"]
        for finding in diagnostics.get("findings", [])
        if finding.get("screenshot")
    }
    diagnostic_items = [item for item in items if item.get("kind") == "diagnostic-state"]
    queued_diagnostic_images = {item["image"] for item in diagnostic_items}
    if queued_diagnostic_images != expected_diagnostic_images:
        fail(
            "diagnostic review queue mismatch: "
            f"queued={sorted(queued_diagnostic_images)} expected={sorted(expected_diagnostic_images)}"
        )

    for item in items:
        image = root / item["image"]
        if not image.exists() or image.stat().st_size == 0:
            fail(f"review image missing/empty for {item['id']}: {image}")
        if item.get("kind") == "slide-state":
            if item.get("slideIndex") is None or item.get("state") is None:
                fail(f"slide-state lacks render identity: {item}")

    prompt = prompt_path.read_text()
    if "independent visual reviewer" not in prompt.lower():
        fail("review prompt does not state independent reviewer role")
    if "visual-review.json" not in prompt:
        fail("review prompt does not specify structured output")

    print(
        "RevealSwift visual-review manifest gate passed: "
        f"{len(items)} review items, {len(slide_items)} rendered states, "
        f"{len(diagnostic_items)} diagnostic overlays"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
