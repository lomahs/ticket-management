"""The demo data, synced through the real pipeline, must reproduce what ui-mock-v0 showed (golden.json)."""

import json
from pathlib import Path

import pytest

from tracker.adapters.clock import FixedClock
from tracker.adapters.fake.dataset import DEMO_TODAY
from tracker.adapters.fake.jira import FakeJira, seed_tracker
from tracker.adapters.persistence.db import Database
from tracker.adapters.persistence.secrets import FernetSecretBox
from tracker.application.sync import SyncFromJira
from tracker.domain.flags import build_functions, issue_flags, progress_pct, worker_of
from tracker.domain.model import Thresholds
from tracker.domain.naming import DEFAULT_ALIASES, DEFAULT_RULE, compile_rule, coverage

GOLDEN = json.loads(Path(__file__).with_name("golden.json").read_text("utf-8"))


@pytest.fixture(scope="module")
def synced(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("golden")
    db = Database(f"sqlite:///{tmp / 'demo.db'}")
    db.migrate()
    with db.uow() as u:
        seed_tracker(u, FernetSecretBox.from_key_file(tmp / "secret.key"))
        u.commit()
    clock = FixedClock(DEMO_TODAY)
    result = SyncFromJira(db.uow, FakeJira(clock), clock).run(full=True)
    assert result.status == "ok", result.error
    with db.uow() as u:
        issues = u.issues.list(["PAY-1100"])
    fns = [f for f in build_functions(issues) if f.epic_key == "PAY-1100"]
    flags = {c.key: issue_flags(c, is_current=(i == f.current), owner_id=f.owner_id, today=DEMO_TODAY, thresholds=Thresholds())
             for f in fns for i, c in enumerate(f.cells) if c}
    return fns, flags


def test_all_twelve_functions_with_five_steps(synced):
    fns, _ = synced
    assert [f.id for f in fns] == [f"F{i:02d}" for i in range(1, 13)]
    assert all(all(f.cells) for f in fns)


def test_subtasks_match_golden(synced):
    fns, flags = synced
    got = {}
    for f in fns:
        for c in f.cells:
            got[c.key] = {"fid": f.id, "step": c.step.value, "status": c.status.value, "assignee": c.assignee_id,
                          "worker": worker_of(c, f.owner_id), "ps": c.planned_start, "pe": c.planned_end,
                          "est": c.estimate_h, "logged": c.logged_h, "rounds": flags[c.key].rounds, "held": flags[c.key].held_days}
    assert got == GOLDEN["subtasks"]


def test_current_step_late_over_and_tone_match_golden(synced):
    fns, flags = synced
    assert {f.id: f.current for f in fns} == GOLDEN["current_step"]
    assert {k: v.late_days for k, v in flags.items() if v.late} == GOLDEN["late"]
    assert sorted(k for k, v in flags.items() if v.over) == GOLDEN["over"]
    assert {k: v.tone for k, v in flags.items()} == GOLDEN["tone"]


def test_matrix_stats_match_golden(synced):
    fns, flags = synced
    cells = [c for f in fns for c in f.cells]
    late_keys = {k for k, v in flags.items() if v.late}
    assert {
        "progress_pct": round(sum(progress_pct(f) for f in fns) / len(fns)),
        "functions_done": sum(1 for f in fns if f.current == 5),
        "late_functions": sum(1 for f in fns if any(c.key in late_keys for c in f.cells)),
        "late_subtasks": len(late_keys),
        "over_subtasks": sum(1 for v in flags.values() if v.over),
        "logged_h": sum(c.logged_h for c in cells),
        "estimate_h": sum(c.estimate_h for c in cells),
    } == GOLDEN["matrix_stats"]


def test_name_rule_now_covers_hyphenated_function_names(synced):
    fns, _ = synced
    summaries = [c.summary for f in fns for c in f.cells]
    # ui-mock-v0 matched 50/60 (F04, F08 split at their hyphen); {step} now only matches aliases.
    assert GOLDEN["name_rule_coverage"]["total"] == len(summaries)
    assert coverage(compile_rule(DEFAULT_RULE, DEFAULT_ALIASES), summaries) == 60
