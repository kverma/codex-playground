#!/usr/bin/env python3
import json
import sys
from pathlib import Path


def main() -> int:
    review_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "diagnostic-artifacts")
    mode = sys.argv[2] if len(sys.argv) > 2 else "needs_changes"

    manifest = json.loads((review_dir / "review-manifest.json").read_text())
    deck = manifest["deck"]
    role = manifest["reviewerRole"]
    items = manifest["items"]

    if mode == "pass":
        result = {
            "schemaVersion": 1,
            "deck": deck,
            "reviewerRole": role,
            "decision": "pass",
            "summary": "Visual review found no blocking presentation-quality issues.",
            "findings": [],
        }
    else:
        slide_item = next((item for item in items if item["kind"] == "slide-state"), None)
        if slide_item is None:
            raise RuntimeError("manifest has no slide-state review item")
        result = {
            "schemaVersion": 1,
            "deck": deck,
            "reviewerRole": role,
            "decision": "needs_changes",
            "summary": "One visible composition issue should be corrected before final delivery.",
            "findings": [
                {
                    "id": "visual-finding-001",
                    "severity": "warning",
                    "category": "composition",
                    "reviewItemID": slide_item["id"],
                    "slideID": slide_item.get("slideID"),
                    "slideIndex": slide_item.get("slideIndex"),
                    "description": "The slide feels visually unbalanced.",
                    "evidence": "The primary content cluster is concentrated on one side of the referenced rendered state.",
                    "suggestedFix": "Rebalance the layout by adjusting column widths or whitespace while preserving the slide hierarchy.",
                }
            ],
        }

    output = review_dir / "visual-review.json"
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
