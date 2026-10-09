"""Captures golden values from the ui-mock-v0 implementation (backend/app).

Run once: .venv/bin/python -m tests.characterization.capture
The output (golden.json) is what the clean-architecture rewrite must reproduce.
"""

import json
from pathlib import Path

from app.domain import all_cells, answer, validate_leave_rows
from app.main import IMPORT_SAMPLE
from app.store import Store

s = Store(None).snapshot()
cells = all_cells(s)
fns = s["functions"]
pct = lambda f: round(sum({"C": 1, "R": 0.8, "W": 0.4, "N": 0}[c["status"]] for c in f["cells"]) / 5 * 100)  # noqa: E731
golden = {
    "today": s["today"],
    "subtasks": {c["key"]: {"fid": c["fid"], "step": c["sk"], "status": c["status"], "assignee": c["assignee"],
                            "worker": c["worker"], "ps": c["ps"], "pe": c["pe"], "est": c["est"], "logged": c["logged"],
                            "rounds": c["rounds"], "held": c["held"]} for c in cells},
    "current_step": {f["id"]: f["cur"] for f in fns},
    "late": {c["key"]: c["lateDays"] for c in cells if c["late"]},
    "over": sorted(c["key"] for c in cells if c["over"]),
    "tone": {c["key"]: c["tone"] for c in cells},
    "matrix_stats": {
        "progress_pct": round(sum(pct(f) for f in fns) / len(fns)),
        "functions_done": sum(1 for f in fns if f["cur"] == 5),
        "late_functions": sum(1 for f in fns if any(c["late"] for c in f["cells"])),
        "late_subtasks": sum(1 for c in cells if c["late"]),
        "over_subtasks": sum(1 for c in cells if c["over"]),
        "logged_h": sum(c["logged"] for c in cells),
        "estimate_h": sum(c["est"] for c in cells),
    },
    "qa_late": answer(s, "Which functions are late?"),
    "leave_import_sample": {r["n"]: r["err"] for r in validate_leave_rows(s, IMPORT_SAMPLE)},
    # Computed by the ui-mock-v0 frontend (SettingsScreen tplRe) with the default rule:
    # "E-wallet payment" (F04) and "End-of-day reconciliation" (F08) split at their hyphen.
    "name_rule_coverage": {"matched": 50, "total": 60, "unmatched_functions": ["F04", "F08"]},
}
Path(__file__).with_name("golden.json").write_text(json.dumps(golden, indent=1, ensure_ascii=False, sort_keys=True), "utf-8")
print("late", golden["late"], "\nstats", golden["matrix_stats"])
