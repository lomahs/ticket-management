"""Demo dataset: the ui-mock-v0 data expressed as Jira issues, plus the tracker-local data an admin would configure.

Everything is deterministic and anchored to DEMO_TODAY. Issues are generated as FakeIssue records that the
FakeJira adapter serves as IssueSnapshots, so the demo runs through the same sync pipeline as a real Jira site.
"""

import math
from dataclasses import dataclass, field

from tracker.domain.calendar import WorkCalendar
from tracker.domain.dates import add_days, days_between, parse, weekday
from tracker.domain.model import Holiday, Leave, LeaveType, Member, Permission, Role
from tracker.domain.naming import DEFAULT_RULE, render_summary
from tracker.domain.steps import STEPS

DEMO_TODAY = "2026-10-07"
UPDATED = "2026-10-06T10:00:00+00:00"

# id, short name, full name, role, country, colour, email, permission, external
MEMBERS = [
    ("an", "An", "Nguyễn Thị An", Role.BA, "VN", "oklch(0.55 0.13 255)", "an.nguyen@acme.vn", Permission.USER, False),
    ("binh", "Bình", "Trần Văn Bình", Role.BA, "VN", "oklch(0.52 0.13 305)", "binh.tran@acme.vn", Permission.USER, False),
    ("chi", "Chi", "Lê Minh Chi", Role.BA, "VN", "oklch(0.56 0.14 35)", "chi.le@acme.vn", Permission.USER, False),
    ("dung", "Dũng", "Phạm Quốc Dũng", Role.BA, "VN", "oklch(0.52 0.11 150)", "dung.pham@acme.vn", Permission.USER, False),
    ("minh", "Minh", "Hoàng Minh", Role.BAL, "VN", "oklch(0.5 0.1 210)", "minh.hoang@acme.vn", Permission.ADMIN, False),
    ("tanaka", "Tanaka", "Tanaka Hiroshi", Role.JM, "JP", "oklch(0.48 0.04 260)", "h.tanaka@client.co.jp", Permission.USER, True),
]


def account_of(member_id: str) -> str:
    return f"acc-{member_id}"


def demo_members() -> list[Member]:
    return [Member(i, n, f, r, c, col, e, p, x, account_of(i)) for i, n, f, r, c, col, e, p, x in MEMBERS]


HOLIDAYS = [
    ("2026-09-01", "VN", "National Day holiday (VN)"), ("2026-09-02", "VN", "National Day (VN)"),
    ("2026-09-21", "JP", "Respect for the Aged Day (JP)"), ("2026-09-22", "JP", "Citizens' Holiday (JP)"),
    ("2026-09-23", "JP", "Autumnal Equinox Day (JP)"), ("2026-10-12", "JP", "Sports Day (JP)"),
    ("2026-11-03", "JP", "Culture Day (JP)"), ("2026-11-23", "JP", "Labor Thanksgiving Day (JP)"),
    ("2026-12-31", "JP", "Year-end holiday (JP)"), ("2027-01-01", "VN JP", "New Year's Day"),
]


def demo_holidays() -> dict[str, Holiday]:
    return {d: Holiday(d, tuple(c.split()), n) for d, c, n in HOLIDAYS}


_LEAVES = [
    ("chi", "2026-10-09", "full"), ("minh", "2026-10-09", "full"), ("an", "2026-10-16", "full"),
    ("dung", "2026-10-13", "full"), ("binh", "2026-10-20", "half"), ("tanaka", "2026-10-14", "halfTent"),
    ("an", "2026-10-22", "fullTent"), ("an", "2026-10-23", "fullTent"), ("chi", "2026-10-28", "fullTent"),
    ("dung", "2026-10-02", "half"), ("binh", "2026-09-25", "full"), ("minh", "2026-10-26", "fullTent"),
    ("tanaka", "2026-10-30", "full"), ("chi", "2026-09-18", "full"),
]


def demo_leaves() -> list[Leave]:
    return [Leave(i + 1, w, d, LeaveType(t)) for i, (w, d, t) in enumerate(_LEAVES)]


EXTRA_DAYS = {"2026-10-10": "Release support · go-live weekend"}

# Epics on the simulated site. The first six are tracked; the last two can be added from Settings.
EPICS = {
    "PAY-1100": ("PAY", "Payment flow revision", "2026-11-20"),
    "PAY-1300": ("PAY", "e-KYC integration", "2026-12-18"),
    "PAY-1420": ("PAY", "Revenue report upgrade", "2027-01-29"),
    "PAY-0980": ("PAY", "Rewards points system migration", "2026-10-16"),
    "CRM-400": ("CRM", "Customer Hub · Loyalty program", "2026-11-30"),
    "OPS-200": ("OPS", "Back-office · Daily operations", "2026-12-31"),
    "PAY-1500": ("PAY", "Refund automation", "2027-03-31"),
    "CRM-450": ("CRM", "Customer Hub · Campaigns", "2027-02-26"),
}
TRACKED_EPICS = ["PAY-1100", "PAY-1300", "PAY-1420", "PAY-0980", "CRM-400", "OPS-200"]

STATUS_NAMES = {"N": ("To Do", "new"), "W": ("In Progress", "indeterminate"), "R": ("Resolved", "done"), "C": ("Closed", "done")}
STATUS_BY_NAME = {"Resolved": "R"}  # Resolved sits in Jira's "done" category but is not closed for the tracker


@dataclass
class FakeIssue:
    """A Jira issue in the simulated site. Assignees and worklog authors are member ids; statuses are N/W/R/C."""

    key: str
    summary: str
    parent: str
    status: str = "N"
    assignee: str | None = None
    ps: str | None = None
    pe: str | None = None
    est_h: float = 0
    created: str = "2026-07-01"
    updated: str = UPDATED
    worklogs: list[tuple[str, str, float, str]] = field(default_factory=list)  # date, member, hours, comment
    changes: list[tuple[str, str, str | None, str | None]] = field(default_factory=list)  # at, field, from, to

    @property
    def project(self) -> str:
        return self.key.split("-")[0]


# --- helpers --------------------------------------------------------------------------------------------------

def _calendar() -> WorkCalendar:
    return WorkCalendar(working_days=(1, 2, 3, 4, 5), extra_days=EXTRA_DAYS, holidays=demo_holidays(),
                        members={m.id: m for m in demo_members()}, leaves=demo_leaves())


def weekdays_before(d: str, n: int) -> str:
    """The date n Mon–Fri days before d (so that Mon–Fri days in (result, d] == n)."""
    while n > 0:
        d = add_days(d, -1)
        if weekday(d) not in (0, 6):
            n -= 1
    return d


STEP_NOTES = {"create": "Write spec", "balr": "Review spec", "fixb": "Address BAL comments", "jmr": "Review spec", "fixj": "Address JM comments"}


def _split_logs(worker: str, logged: float, pe: str | None, note: str) -> list[tuple[str, str, float, str]]:
    """ui-mock-v0 worklog split: up to 4 entries walking back from the planned end (or today)."""
    out, left = [], logged
    d = pe if pe and pe < DEMO_TODAY else DEMO_TODAY
    i = 0
    while left > 0 and i < 4:
        h = min(left, left / 2 if i == 0 and left > 4 else 4)
        out.append((d, worker, h, note))
        left -= h
        d = add_days(d, -1)
        while weekday(d) % 6 == 0:
            d = add_days(d, -1)
        i += 1
    return out


def _handover_events(*, status: str, assignee: str, owner: str, reviewer: str, fix: bool, rounds: int, held: int,
                     anchor_default: str) -> list[tuple[str, str, str | None, str | None]]:
    """Changelog that reproduces review rounds and days held with the current assignee."""
    ev: list[tuple[str, str, str | None, str | None]] = []
    anchor = weekdays_before(DEMO_TODAY, held) if held else anchor_default
    if status == "R" and held:
        ev.append((anchor, "status", "W", "R"))
        anchor = weekdays_before(anchor, 1)
    if fix and (rounds >= 2 or held):
        seq: list[tuple[str, str]] = []
        for k in range(rounds):
            seq.append((reviewer, owner))
            if k < rounds - 1:
                seq.append((owner, reviewer))
        if assignee != owner:
            seq.append((owner, assignee))
        d = anchor
        dated = []
        for frm, to in reversed(seq):
            dated.append((d, "assignee", frm, to))
            d = weekdays_before(d, 1)
        ev = list(reversed(dated)) + ev
    elif held and not fix:
        ev.insert(0, (anchor, "assignee", owner if owner != assignee else reviewer, assignee))
    return sorted(ev, key=lambda e: e[0])


class _Keys:
    def __init__(self, prefix: str, start: int, reserved: set[str]):
        self.prefix, self.n, self.reserved = prefix, start, reserved

    def next(self) -> str:
        while True:
            k = f"{self.prefix}-{self.n:04d}" if self.n < 1000 else f"{self.prefix}-{self.n}"
            self.n += 1
            if k not in self.reserved:
                self.reserved.add(k)
                return k


def _summary(phase: str, fid: str, name: str, step_label: str) -> str:
    return render_summary(DEFAULT_RULE, phase_name=phase, function_id=fid, function_name=name, step=step_label)


# --- PAY-1100: the anken shown in the Function Matrix of ui-mock-v0 ------------------------------------------------

_N0 = ("N", 0, 2)
# (function id, name, BA, start, [(status, logged, estimate, {r: rounds, h: holder, d: held days, s/e: fixed plan})])
_SPEC = [
    ("F01", "Payment method selection", "an", "2026-09-14", [("C", 10, 10), ("C", 2, 2), ("C", 3, 3, {"r": 1}), ("C", 2, 2), ("C", 1.5, 2, {"r": 1})]),
    ("F02", "OTP verification", "an", "2026-09-16", [("C", 8, 8), ("C", 2, 2), ("C", 2.5, 2, {"r": 2}), ("C", 2, 2), ("W", 1, 2, {"r": 1, "s": "2026-10-07", "e": "2026-10-08", "d": 1})]),
    ("F03", "Domestic card payment", "binh", "2026-09-21", [("C", 12, 10), ("C", 3, 2), ("W", 7.5, 4, {"r": 3, "d": 2, "s": "2026-10-01", "e": "2026-10-06"}), _N0, _N0]),
    ("F04", "E-wallet payment", "binh", "2026-09-18", [("C", 9, 10), ("C", 2, 2), ("C", 2, 3, {"r": 1}), ("W", 1, 2, {"d": 6, "s": "2026-09-30", "e": "2026-10-02"}), _N0]),
    ("F05", "Partial refund", "chi", "2026-10-01", [("C", 8, 8), ("W", 1, 2, {"d": 3}), ("N", 0, 3), _N0, _N0]),
    ("F06", "Transaction history", "chi", "2026-09-28", [("W", 11, 8, {"e": "2026-10-05"}), _N0, _N0, _N0, _N0]),
    ("F07", "Payment result notification", "dung", "2026-09-14", [("C", 8, 8), ("C", 2, 2), ("C", 3, 3, {"r": 2}), ("C", 2, 2), ("W", 5, 3, {"r": 3, "h": "tanaka", "d": 4, "s": "2026-09-28", "e": "2026-10-02"})]),
    ("F08", "End-of-day reconciliation", "dung", "2026-10-05", [("C", 6, 8), ("W", 0.5, 2, {"d": 1}), ("N", 0, 3), ("W", 0.5, 2, {"d": 1}), _N0]),
    ("F09", "Payment gateway timeout handling", "an", "2026-10-12", [("N", 0, 8), _N0, _N0, _N0, _N0]),
    ("F10", "Transaction fee configuration", "binh", "2026-09-24", [("C", 8, 8), ("C", 2, 2), ("R", 3, 3, {"r": 2, "h": "minh", "d": 5, "e": "2026-10-01"}), _N0, _N0]),
    ("F11", "Payment report export", "dung", "2026-10-01", [("C", 7, 8), ("W", 1.5, 2, {"d": 4}), _N0, _N0, _N0]),
    ("F12", "Retry failed payment", "chi", "2026-10-05", [("C", 7.5, 8), ("C", 0.5, 1), ("W", 1, 3, {"r": 1, "s": "2026-10-07", "e": "2026-10-09"}), _N0, _N0]),
]

_PAY1100_PREPARE_DONE = [
    ("PAY-1104", "Kick-off with JM", "minh", "2026-09-01", "2026-09-02", 4), ("PAY-1105", "Collect current payment flow", "an", "2026-09-01", "2026-09-03", 8),
    ("PAY-1106", "As-is flow diagram", "binh", "2026-09-02", "2026-09-04", 8), ("PAY-1107", "Gap analysis", "chi", "2026-09-03", "2026-09-07", 8),
    ("PAY-1108", "Screen list", "dung", "2026-09-04", "2026-09-08", 6), ("PAY-1109", "Message list", "an", "2026-09-07", "2026-09-09", 6),
    ("PAY-1110", "Error code inventory", "binh", "2026-09-08", "2026-09-10", 6), ("PAY-1111", "Function breakdown", "chi", "2026-09-09", "2026-09-11", 8),
    ("PAY-1112", "Estimate review", "minh", "2026-09-10", "2026-09-11", 4),
]

_PAY1100_EXTRAS = [  # key, title, phase parent, assignee, status, estimate, logged, start, end
    ("PAY-1180", "Prepare Q&A list for JM", "PAY-1101", "an", "W", 4, 3, "2026-10-02", "2026-10-06"),
    ("PAY-1261", "UT – F01 Payment method selection", "PAY-1103", "an", "W", 6, 2, "2026-10-06", "2026-10-07"),
    ("PAY-1262", "UT – F02 OTP verification", "PAY-1103", "an", "N", 6, 0, "2026-10-09", "2026-10-09"),
    ("PAY-1183", "Prepare domestic card test data", "PAY-1101", "binh", "W", 3, 1, "2026-10-06", "2026-10-07"),
    ("PAY-1184", "Update card payment error screen", "PAY-1101", "binh", "W", 4, 5, "2026-10-01", "2026-10-05"),
    ("PAY-1185", "Answer JM Q&A on card limits", "PAY-1101", "binh", "N", 2, 0, "2026-10-05", "2026-10-06"),
    ("PAY-1186", "Cross-check payment gateway error codes", "PAY-1101", "binh", "W", 3, 1.5, "2026-10-02", "2026-10-02"),
    ("PAY-1187", "Revise e-wallet flow diagram", "PAY-1101", "binh", "W", 2, 1, "2026-10-07", "2026-10-07"),
    ("PAY-1188", "Review F10 handover checklist", "PAY-1101", "binh", "N", 1.5, 0, "2026-10-07", "2026-10-07"),
    ("PAY-1265", "UT – F03 Domestic card payment", "PAY-1103", "binh", "W", 6, 2, "2026-10-06", "2026-10-07"),
    ("PAY-1266", "UT – F10 Transaction fee configuration", "PAY-1103", "binh", "N", 5, 0, "2026-10-08", "2026-10-08"),
    ("PAY-1189", "Prepare e-wallet test data", "PAY-1101", "binh", "N", 3, 0, "2026-10-08", "2026-10-08"),
    ("PAY-1190", "Update fee mapping document", "PAY-1101", "binh", "N", 2, 0, "2026-10-08", "2026-10-08"),
    ("PAY-1191", "Clarify refund requirements with JM", "PAY-1101", "binh", "N", 1, 0, "2026-10-08", "2026-10-08"),
    ("PAY-1263", "UT – F04 E-wallet payment", "PAY-1103", "binh", "N", 6, 0, "2026-10-08", "2026-10-09"),
    ("PAY-1181", "Update refund flow document", "PAY-1101", "chi", "W", 4, 2.5, "2026-10-06", "2026-10-08"),
    ("PAY-1264", "UT – F07 Payment result notification", "PAY-1103", "dung", "N", 6, 0, "2026-10-14", "2026-10-15"),
    ("PAY-1182", "Review Prepare overview document", "PAY-1101", "minh", "W", 3, 1, "2026-10-07", "2026-10-09"),
]


def _pay1100(cal: WorkCalendar) -> list[FakeIssue]:
    out = [
        FakeIssue("PAY-1101", "[Prepare] Payment flow revision", "PAY-1100", "W", "minh", "2026-09-01", "2026-10-09"),
        FakeIssue("PAY-1102", "[Implement_Coding] Payment flow revision", "PAY-1100", "W", "minh", "2026-09-14", "2026-10-30"),
        FakeIssue("PAY-1103", "[Function Unit Test] Payment flow revision", "PAY-1100", "N", "minh", "2026-11-02", "2026-11-20"),
    ]
    for key, title, who, ps, pe, est in _PAY1100_PREPARE_DONE:
        out.append(FakeIssue(key, title, "PAY-1101", "C", who, ps, pe, est, "2026-08-28", worklogs=_split_logs(who, est, pe, "Work on task")))
    k = 1201
    for fid, name, ba, start, steps in _SPEC:
        cur = start
        for si, raw in enumerate(steps):
            st, logged, est = raw[0], raw[1], raw[2]
            o = raw[3] if len(raw) > 3 else {}
            step = STEPS[si]
            reviewer = "minh" if step.kind.value == "balr" else "tanaka" if step.kind.value == "jmr" else None
            who = reviewer or o.get("h") or ba
            if o.get("e"):
                ps, pe = o.get("s") or o["e"], o["e"]
            else:
                frm = add_days(DEMO_TODAY, 1) if st == "N" and cur <= DEMO_TODAY else cur
                ps, pe = cal.span(frm, step.duration_days, who)
            cur = add_days(pe, 1)
            worker = who if step.review else ba
            fix_reviewer = "tanaka" if step.kind.value == "fixj" else "minh"
            out.append(FakeIssue(
                f"PAY-{k}", _summary("Implement_Coding", fid, name, step.label), "PAY-1102", st, who, ps, pe, est,
                created=add_days(start, -3),
                worklogs=_split_logs(worker, logged, pe, STEP_NOTES[step.kind.value]),
                changes=_handover_events(status=st, assignee=who, owner=ba, reviewer=reviewer or fix_reviewer, fix=step.fix,
                                         rounds=o.get("r", 0), held=o.get("d", 0), anchor_default=ps),
            ))
            k += 1
    for key, title, parent, who, st, est, logged, ps, pe in _PAY1100_EXTRAS:
        out.append(FakeIssue(key, title, parent, st, who, ps, pe, est, "2026-09-28",
                             worklogs=[("2026-10-06", who, logged, "Work on task")] if logged else []))
    return out


# --- other anken: generated sub-tasks shaped like their epic roll-ups -----------------------------------------------

@dataclass(frozen=True)
class _Fn:
    fid: str
    name: str
    ba: str
    statuses: str  # one letter per step; "*" keeps a pre-defined issue (see _FIXED)


_FIXED = {  # pre-defined sub-tasks of other anken: (anken, fid, step index) → overrides
    ("PAY-1300", "K04", 2): dict(key="PAY-1342", status="W", assignee="chi", est=3, logged=4, ps="2026-09-30", pe="2026-10-02", r=3, d=3),
    ("PAY-1300", "K06", 3): dict(key="PAY-1351", status="W", assignee="tanaka", est=2, logged=0.5, ps="2026-09-29", pe="2026-10-01", d=5),
    ("PAY-0980", "M04", 4): dict(key="PAY-0996", status="W", assignee="tanaka", est=2, logged=3, ps="2026-09-25", pe="2026-09-30", r=4, d=3),
}


def _phase_tasks(keys: _Keys, parent: str, titles: list[str], who: list[str], statuses: str, start: str, end: str,
                 est: float) -> list[FakeIssue]:
    days = [d for d in days_between(start, end) if weekday(d) not in (0, 6)]
    out = []
    for i, t in enumerate(titles):
        ps = days[min(len(days) - 1, i * max(1, len(days) // max(1, len(titles))))]
        pe = days[min(len(days) - 1, days.index(ps) + 2)]
        st = statuses[i]
        logged = est if st == "C" else est / 2 if st in "WR" else 0
        out.append(FakeIssue(keys.next(), t, parent, st, who[i % len(who)], ps, pe, est, add_days(start, -5),
                             worklogs=_split_logs(who[i % len(who)], logged, pe, "Work on task")))
    return out


def _functions(cal: WorkCalendar, keys: _Keys, anken: str, parent: str, fns: list[_Fn], start: str, reviewer: str,
               jm: str, phase: str = "Implement_Coding") -> list[FakeIssue]:
    out = []
    day = start
    for idx, f in enumerate(fns):
        cur = cal.next_work(day, f.ba)
        for si, st in enumerate(f.statuses):
            step = STEPS[si]
            fixed = _FIXED.get((anken, f.fid, si))
            who = {"balr": reviewer, "jmr": jm}.get(step.kind.value, f.ba)
            est = 8 if si == 0 else math.floor(8 * step.effort_weight * 2 + 0.5) / 2
            ps, pe = cal.span(cur, step.duration_days, who)
            cur = add_days(pe, 1)
            if fixed:
                st, who, est, ps, pe = fixed["status"], fixed["assignee"], fixed["est"], fixed["ps"], fixed["pe"]
            logged = fixed["logged"] if fixed else est if st == "C" else est / 2 if st in "WR" else 0
            worker = who if step.review else f.ba
            out.append(FakeIssue(
                fixed["key"] if fixed else keys.next(), _summary(phase, f.fid, f.name, step.label), parent, st, who, ps, pe, est,
                add_days(start, -3), worklogs=_split_logs(worker, logged, pe, STEP_NOTES[step.kind.value]),
                changes=_handover_events(status=st, assignee=who, owner=f.ba, reviewer=jm if step.kind.value == "fixj" else reviewer,
                                         fix=step.fix, rounds=fixed.get("r", 0) if fixed else (1 if step.fix and st != "N" else 0),
                                         held=fixed.get("d", 0) if fixed else 0, anchor_default=ps),
            ))
        day = add_days(day, 3 if idx % 2 else 4)
    return out


def _other_anken(cal: WorkCalendar, reserved: set[str]) -> list[FakeIssue]:
    out: list[FakeIssue] = []
    # PAY-1300 e-KYC integration: Prepare done, Implement about a third through, UT not started.
    k = _Keys("PAY", 1304, reserved)
    out += [FakeIssue("PAY-1301", "[Prepare] e-KYC integration", "PAY-1300", "C", "minh", "2026-09-01", "2026-09-25"),
            FakeIssue("PAY-1302", "[Implement_Coding] e-KYC integration", "PAY-1300", "W", "minh", "2026-09-28", "2026-11-27"),
            FakeIssue("PAY-1303", "[Function Unit Test] e-KYC integration", "PAY-1300", "N", "minh", "2026-11-30", "2026-12-18")]
    out += _phase_tasks(k, "PAY-1301", ["e-KYC vendor comparison", "Regulation checklist", "Customer journey map", "Screen list",
                                        "Message list", "Error code list", "Function breakdown", "Estimate review"],
                        ["an", "chi", "minh"], "CCCCCCCC", "2026-09-01", "2026-09-25", 6)
    out += _functions(cal, k, "PAY-1300", "PAY-1302", [
        _Fn("K01", "ID card upload", "an", "CCCCC"), _Fn("K02", "OCR result review", "chi", "CCCCC"),
        _Fn("K03", "Liveness check", "an", "CCCWN"), _Fn("K04", "ID card capture", "chi", "CCWNN"),
        _Fn("K05", "Address verification", "an", "CWNNN"), _Fn("K06", "Face matching", "chi", "CCCWN"),
        _Fn("K07", "Risk scoring", "an", "WNNNN"), _Fn("K08", "Manual review queue", "chi", "NNNNN"),
    ], "2026-09-14", "minh", "tanaka")
    out += _phase_tasks(k, "PAY-1303", [f"UT – K0{i} test cases" for i in range(1, 9)] + ["UT – Regression pack", "UT – Vendor sandbox run",
                                                                                       "UT – Evidence review", "UT – Sign-off"],
                        ["an", "chi"], "N" * 12, "2026-11-30", "2026-12-18", 6)
    # PAY-1420 Revenue report upgrade: Prepare under way, nothing implemented yet.
    k = _Keys("PAY", 1424, reserved)
    out += [FakeIssue("PAY-1421", "[Prepare] Revenue report upgrade", "PAY-1420", "W", "minh", "2026-09-28", "2026-10-16"),
            FakeIssue("PAY-1422", "[Implement_Coding] Revenue report upgrade", "PAY-1420", "N", "minh", "2026-10-19", "2026-12-18"),
            FakeIssue("PAY-1423", "[Function Unit Test] Revenue report upgrade", "PAY-1420", "N", "minh", "2026-12-21", "2027-01-29")]
    out += _phase_tasks(k, "PAY-1421", ["Current report inventory", "KPI definitions", "Data source mapping", "Report mock-ups",
                                        "Estimate review"], ["dung", "binh", "minh"], "CCCWW", "2026-09-28", "2026-10-16", 6)
    out += _functions(cal, k, "PAY-1420", "PAY-1422", [
        _Fn(f"R0{i}", n, b, "NNNNN") for i, (n, b) in enumerate([("Daily revenue summary", "dung"), ("Revenue by channel", "binh"),
                                                                  ("Refund adjustments", "dung"), ("Fee breakdown", "binh"),
                                                                  ("Monthly close report", "dung"), ("Export to accounting", "binh"),
                                                                  ("Report scheduler", "dung")], start=1)
    ], "2026-10-19", "minh", "tanaka")
    out += _phase_tasks(k, "PAY-1423", [f"UT – R0{i} test cases" for i in range(1, 8)] + ["UT – Data reconciliation", "UT – Performance check",
                                                                                       "UT – Sign-off"],
                        ["dung", "binh"], "N" * 10, "2026-12-21", "2027-01-29", 6)
    # PAY-0980 Rewards points system migration: implemented, finishing UT.
    k = _Keys("PAY", 981, reserved)
    reserved |= {"PAY-0996", "PAY-1009", "PAY-1012", "PAY-1015"}
    out += [FakeIssue("PAY-0981", "[Prepare] Rewards points system migration", "PAY-0980", "C", "minh", "2026-07-01", "2026-07-31"),
            FakeIssue("PAY-0982", "[Implement_Coding] Rewards points system migration", "PAY-0980", "C", "minh", "2026-08-03", "2026-09-18"),
            FakeIssue("PAY-0983", "[Function Unit Test] Rewards points system migration", "PAY-0980", "W", "minh", "2026-09-21", "2026-10-16")]
    k.n = 984
    out += _phase_tasks(k, "PAY-0981", ["Legacy points audit", "Migration rules", "Partner list", "Cut-over plan", "Rollback plan",
                                        "Estimate review"], ["binh", "chi", "dung"], "CCCCCC", "2026-07-01", "2026-07-31", 6)
    names = ["Points accrual rules", "Points balance view", "Points transfer", "Points accrual", "Tier upgrade", "Tier downgrade",
             "Partner points sync", "Points expiry job", "Points redemption", "Statement export", "Points expiry"]
    out += _functions(cal, k, "PAY-0980", "PAY-0982",
                      [_Fn(f"M{i:02d}", n, ["binh", "chi", "dung"][i % 3], "CCCCW" if i == 4 else "CCCCC") for i, n in enumerate(names, start=1)],
                      "2026-08-03", "minh", "tanaka")
    ut = _phase_tasks(k, "PAY-0983", [f"UT – M{i:02d} {n}" for i, n in enumerate(names, start=1) if i not in (6, 9, 11)],
                      ["binh", "chi", "dung"], "CCCCCCCC", "2026-09-21", "2026-10-02", 6)
    ut += [FakeIssue("PAY-1012", "UT – M09 Points redemption", "PAY-0983", "W", "binh", "2026-09-28", "2026-10-02", 8,
                     worklogs=[("2026-10-05", "binh", 9, "Work on task")]),
           FakeIssue("PAY-1015", "UT – M11 Points expiry", "PAY-0983", "W", "dung", "2026-10-01", "2026-10-05", 6,
                     worklogs=[("2026-10-05", "dung", 4, "Work on task")]),
           FakeIssue("PAY-1009", "UT – M06 Partner points sync", "PAY-0983", "R", "chi", "2026-09-24", "2026-09-29", 6,
                     worklogs=[("2026-10-05", "chi", 6, "Work on task")],
                     changes=[(weekdays_before(DEMO_TODAY, 4), "status", "W", "R")])]
    out += ut
    return out


# --- CRM / OPS: tasks that do not follow the function naming rule ---------------------------------------------------

_OTHER = [
    ("CRM-412", "Customer segment rules – Create", "an", "2026-10-05", "2026-10-09", 16, 8, ["2026-10-06"]),
    ("CRM-418", "Loyalty tier spec – BAL Review", "minh", "2026-10-12", "2026-10-13", 4, 0, None),
    ("CRM-405", "Contact merge flow – Create", "binh", "2026-09-21", "2026-09-25", 14, 15, ["2026-09-22", "2026-09-28"]),
    ("CRM-430", "Campaign export – Create", "binh", "2026-10-12", "2026-10-16", 16, 0, None),
    ("OPS-221", "Refund approval screen – Create", "chi", "2026-10-13", "2026-10-16", 12, 0, None),
    ("OPS-219", "Daily ops report – Create", "dung", "2026-09-28", "2026-10-02", 12, 12, ["2026-09-28", "2026-10-02"]),
    ("OPS-225", "Ops dashboard – JM Review", "tanaka", "2026-10-08", "2026-10-09", 3, 0, None),
    ("OPS-230", "Role permissions – Create", "dung", "2026-10-19", "2026-10-23", 16, 0, None),
    ("CRM-433", "Segment rules – JM Review", "tanaka", "2026-10-19", "2026-10-20", 3, 0, None),
    ("OPS-226", "Daily ops report – BAL Review", "minh", "2026-10-05", "2026-10-06", 3, 3, ["2026-10-05", "2026-10-06"]),
    ("CRM-420", "Loyalty tier spec – Create", "an", "2026-09-28", "2026-10-02", 14, 13, ["2026-09-28", "2026-10-02"]),
    ("OPS-232", "Audit log export – Create", "chi", "2026-10-19", "2026-10-22", 10, 0, None),
]
MEETING_KEY = "OPS-201"


def _crm_ops() -> list[FakeIssue]:
    out = [FakeIssue("CRM-401", "[Implement_Coding] Customer Hub · Loyalty program", "CRM-400", "W", "minh", "2026-09-21", "2026-11-30"),
           FakeIssue("OPS-205", "[Implement_Coding] Back-office · Daily operations", "OPS-200", "W", "minh", "2026-09-28", "2026-12-31"),
           FakeIssue("OPS-210", "[Operation] Team operations", "OPS-200", "W", "minh", None, None),
           FakeIssue(MEETING_KEY, "Internal meeting / support", "OPS-210", "W", "minh", None, None, 0, "2026-08-31")]
    for key, title, who, ps, pe, est, logged, act in _OTHER:
        parent = "CRM-401" if key.startswith("CRM") else "OPS-205"
        logs = []
        if act:
            logs = [(act[0], who, logged, "Work on task")] if len(act) == 1 else [(act[0], who, logged / 2, "Work on task"),
                                                                                (act[1], who, logged - logged / 2, "Work on task")]
        status = "C" if act and len(act) > 1 else "W" if logged else "N"
        out.append(FakeIssue(key, title, parent, status, who, ps, pe, est, "2026-09-14", worklogs=logs))
    return out


# --- timesheet fill: meetings and support logged on the operations ticket ------------------------------------------

_PAT = [8, 8, 8, 7.5, 8, 8, 6.5, 8, 9.5, 8, 8, 0, 8, 8.5, 8, 7, 8, 10]
_WEEK = {"an": [8, 8, 4], "binh": [8, 7.5, 3], "chi": [7.5, 8, 2.5], "dung": [8, 6, 3], "minh": [8, 8, 5], "tanaka": [7, 7.5, 4]}


def _target_hours(member_id: str, d: str, cal: WorkCalendar) -> float:
    if d >= "2026-10-05":
        i = (parse(d) - parse("2026-10-05")).days
        h = _WEEK[member_id][i] if i < 3 else 0
    else:
        n = int(d[8:]) + int(d[5:7]) * 31
        h = _PAT[(n * 7 + len(member_id) * 3 + ord(member_id[0])) % len(_PAT)]
    lv = cal.leave(member_id, d)
    return min(h, 4) if lv and lv.type == LeaveType.HALF else h


def _fill_meetings(issues: list[FakeIssue], cal: WorkCalendar) -> None:
    logged: dict[tuple[str, str], float] = {}
    for i in issues:
        for d, who, h, _ in i.worklogs:
            logged[(who, d)] = logged.get((who, d), 0) + h
    meeting = next(i for i in issues if i.key == MEETING_KEY)
    for mid, *_ in MEMBERS:
        for d in days_between("2026-09-01", DEMO_TODAY):
            if cal.off(d, mid):
                continue
            gap = _target_hours(mid, d, cal) - logged.get((mid, d), 0)
            if gap > 0:
                meeting.worklogs.append((d, mid, gap, "Meeting / support"))


def build_issues() -> list[FakeIssue]:
    cal = _calendar()
    issues = _pay1100(cal)
    reserved = {i.key for i in issues} | {"PAY-1342", "PAY-1351"}
    issues += _other_anken(cal, reserved)
    issues += _crm_ops()
    _fill_meetings(issues, cal)
    return issues
