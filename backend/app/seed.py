"""Demo dataset, transcribed from the Team Tracker design.

It stands in for the first full sync from Jira. `build_initial_state()` returns a
JSON-serialisable dict that the Store persists and mutates.
"""

import copy

from .dates import add, js_round, js_weekday, parse
from .domain import STEPS, Calendar, task_title

TODAY = "2026-10-07"

MEMBERS = {
    "an": {"name": "An", "full": "Nguyễn Thị An", "role": "BA", "c": "VN", "color": "oklch(0.55 0.13 255)", "email": "an.nguyen@acme.vn"},
    "binh": {"name": "Bình", "full": "Trần Văn Bình", "role": "BA", "c": "VN", "color": "oklch(0.52 0.13 305)", "email": "binh.tran@acme.vn"},
    "chi": {"name": "Chi", "full": "Lê Minh Chi", "role": "BA", "c": "VN", "color": "oklch(0.56 0.14 35)", "email": "chi.le@acme.vn"},
    "dung": {"name": "Dũng", "full": "Phạm Quốc Dũng", "role": "BA", "c": "VN", "color": "oklch(0.52 0.11 150)", "email": "dung.pham@acme.vn"},
    "minh": {"name": "Minh", "full": "Hoàng Minh", "role": "BAL", "c": "VN", "color": "oklch(0.5 0.1 210)", "email": "minh.hoang@acme.vn", "perm": "admin"},
    "tanaka": {"name": "Tanaka", "full": "Tanaka Hiroshi", "role": "JM", "c": "JP", "color": "oklch(0.48 0.04 260)", "email": "h.tanaka@client.co.jp", "ext": True},
}

CONFIG = {"hours": 8, "days": [1, 2, 3, 4, 5], "tsTol": 1, "soon": 2, "effWarn": 80, "held": 3, "rounds": 3, "cap": 100}

HOLIDAYS = {
    "2026-09-01": ["VN", "National Day holiday (VN)"],
    "2026-09-02": ["VN", "National Day (VN)"],
    "2026-09-21": ["JP", "Respect for the Aged Day (JP)"],
    "2026-09-22": ["JP", "Citizens' Holiday (JP)"],
    "2026-09-23": ["JP", "Autumnal Equinox Day (JP)"],
    "2026-10-12": ["JP", "Sports Day (JP)"],
    "2026-11-03": ["JP", "Culture Day (JP)"],
    "2026-11-23": ["JP", "Labor Thanksgiving Day (JP)"],
    "2026-12-31": ["JP", "Year-end holiday (JP)"],
    "2027-01-01": ["VN JP", "New Year's Day"],
}

_LEAVES = [
    ("chi", "2026-10-09", "full"), ("minh", "2026-10-09", "full"), ("an", "2026-10-16", "full"),
    ("dung", "2026-10-13", "full"), ("binh", "2026-10-20", "half"), ("tanaka", "2026-10-14", "halfTent"),
    ("an", "2026-10-22", "fullTent"), ("an", "2026-10-23", "fullTent"), ("chi", "2026-10-28", "fullTent"),
    ("dung", "2026-10-02", "half"), ("binh", "2026-09-25", "full"), ("minh", "2026-10-26", "fullTent"),
    ("tanaka", "2026-10-30", "full"), ("chi", "2026-09-18", "full"),
]

EXTRA_DAYS = {"2026-10-10": "Release support · go-live weekend"}

PROJECTS = {
    "PAY": {"name": "PAY · Payment Platform", "c": "oklch(0.52 0.14 255)"},
    "CRM": {"name": "CRM · Customer Hub", "c": "oklch(0.5 0.11 165)"},
    "OPS": {"name": "OPS · Back-office", "c": "oklch(0.56 0.14 50)"},
}

# Epics that exist on the (simulated) Jira site; settings can only track these.
EPIC_CATALOG = {
    "PAY-1100": ["PAY", "Payment flow revision"],
    "PAY-1150": ["PAY", "e-KYC integration"],
    "PAY-1180": ["PAY", "Revenue report upgrade"],
    "PAY-1190": ["PAY", "Rewards points system migration"],
    "CRM-400": ["CRM", "Customer Hub · Loyalty program"],
    "CRM-450": ["CRM", "Customer Hub · Campaigns"],
    "OPS-200": ["OPS", "Back-office · Daily operations"],
}

JIRA_FIELDS = [
    {"value": "", "label": "— Not mapped —", "t": ""},
    {"value": "customfield_10015", "label": "Start date · customfield_10015", "t": "date"},
    {"value": "customfield_10016", "label": "Planned End · customfield_10016", "t": "date"},
    {"value": "customfield_10020", "label": "Planned Start · customfield_10020", "t": "date"},
    {"value": "duedate", "label": "Due date · duedate", "t": "date"},
    {"value": "timeoriginalestimate", "label": "Original estimate · timeoriginalestimate", "t": "number"},
    {"value": "customfield_10040", "label": "Effort (h) · customfield_10040", "t": "number"},
]

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

STEP_NOTES = {"create": "Write spec", "balr": "Review spec", "fixb": "Address BAL comments", "jmr": "Review spec", "fixj": "Address JM comments"}

_EXTRAS = [
    ("PAY-1180", "Prepare Q&A list for JM", "Prepare", "an", "W", 4, 3, "2026-10-02", "2026-10-06"),
    ("PAY-1261", "UT – F01 Payment method selection", "Function Unit Test", "an", "W", 6, 2, "2026-10-06", "2026-10-07"),
    ("PAY-1262", "UT – F02 OTP verification", "Function Unit Test", "an", "N", 6, 0, "2026-10-09", "2026-10-09"),
    ("PAY-1183", "Prepare domestic card test data", "Prepare", "binh", "W", 3, 1, "2026-10-06", "2026-10-07"),
    ("PAY-1184", "Update card payment error screen", "Prepare", "binh", "W", 4, 5, "2026-10-01", "2026-10-05"),
    ("PAY-1185", "Answer JM Q&A on card limits", "Prepare", "binh", "N", 2, 0, "2026-10-05", "2026-10-06"),
    ("PAY-1186", "Cross-check payment gateway error codes", "Prepare", "binh", "W", 3, 1.5, "2026-10-02", "2026-10-02"),
    ("PAY-1187", "Revise e-wallet flow diagram", "Prepare", "binh", "W", 2, 1, "2026-10-07", "2026-10-07"),
    ("PAY-1188", "Review F10 handover checklist", "Prepare", "binh", "N", 1.5, 0, "2026-10-07", "2026-10-07"),
    ("PAY-1265", "UT – F03 Domestic card payment", "Function Unit Test", "binh", "W", 6, 2, "2026-10-06", "2026-10-07"),
    ("PAY-1266", "UT – F10 Transaction fee configuration", "Function Unit Test", "binh", "N", 5, 0, "2026-10-08", "2026-10-08"),
    ("PAY-1189", "Prepare e-wallet test data", "Prepare", "binh", "N", 3, 0, "2026-10-08", "2026-10-08"),
    ("PAY-1190", "Update fee mapping document", "Prepare", "binh", "N", 2, 0, "2026-10-08", "2026-10-08"),
    ("PAY-1191", "Clarify refund requirements with JM", "Prepare", "binh", "N", 1, 0, "2026-10-08", "2026-10-08"),
    ("PAY-1263", "UT – F04 E-wallet payment", "Function Unit Test", "binh", "N", 6, 0, "2026-10-08", "2026-10-09"),
    ("PAY-1181", "Update refund flow document", "Prepare", "chi", "W", 4, 2.5, "2026-10-06", "2026-10-08"),
    ("PAY-1264", "UT – F07 Payment result notification", "Function Unit Test", "dung", "N", 6, 0, "2026-10-14", "2026-10-15"),
    ("PAY-1182", "Review Prepare overview document", "Prepare", "minh", "W", 3, 1, "2026-10-07", "2026-10-09"),
]

_STATICS = [
    {"key": "PAY-1342", "title": "[Implement_Coding][K04] ID card capture – Fix BAL", "assignee": "chi", "status": "W", "est": 3, "logged": 4, "ps": "2026-09-30", "pe": "2026-10-02", "rounds": 3, "held": 3, "isFix": True, "ba": "chi", "anken": "PAY-1300"},
    {"key": "PAY-1351", "title": "[Implement_Coding][K06] Face matching – JM Review", "assignee": "tanaka", "status": "W", "est": 2, "logged": 0.5, "ps": "2026-09-29", "pe": "2026-10-01", "held": 5, "anken": "PAY-1300"},
    {"key": "PAY-1012", "title": "UT – M09 Points redemption", "assignee": "binh", "status": "W", "est": 8, "logged": 9, "ps": "2026-09-28", "pe": "2026-10-02", "anken": "PAY-0980"},
    {"key": "PAY-1015", "title": "UT – M11 Points expiry", "assignee": "dung", "status": "W", "est": 6, "logged": 4, "ps": "2026-10-01", "pe": "2026-10-05", "anken": "PAY-0980"},
    {"key": "PAY-1009", "title": "UT – M06 Partner points sync", "assignee": "chi", "status": "R", "est": 6, "logged": 6, "ps": "2026-09-24", "pe": "2026-09-29", "held": 4, "anken": "PAY-0980"},
    {"key": "PAY-0996", "title": "[Implement_Coding][M04] Points accrual – Fix JM", "assignee": "tanaka", "status": "W", "est": 2, "logged": 3, "ps": "2026-09-25", "pe": "2026-09-30", "rounds": 4, "held": 3, "isFix": True, "ba": "binh", "anken": "PAY-0980"},
]

ANKEN = [
    # The tracked anken: Implement progress, late functions and effort are computed live from its sub-tasks;
    # baseUsed/baseEst are epic hours not covered by synced sub-tasks (Prepare phase, unsynced UT estimates).
    {"key": "PAY-1100", "name": "Payment flow revision", "plan": "2026-11-20", "forecast": "2026-11-27", "phases": [100, 0, 4],
     "members": ["an", "binh", "chi", "dung", "minh", "tanaka"], "dates": ["01/09 – 11/09", "14/09 – 30/10", "02/11 – 20/11"],
     "counts": ["9/9", "", "0/12"], "baseUsed": 62, "baseEst": 136, "matrix": True},
    {"key": "PAY-1300", "name": "e-KYC integration", "plan": "2026-12-18", "forecast": "2026-12-18", "late": 1, "used": 212, "est": 620, "phases": [100, 34, 0], "members": ["an", "chi", "minh", "tanaka"], "dates": ["01/09 – 25/09", "28/09 – 27/11", "30/11 – 18/12"], "counts": ["8/8", "14/41", "0/12"]},
    {"key": "PAY-1420", "name": "Revenue report upgrade", "plan": "2027-01-29", "forecast": "2027-01-22", "late": 0, "used": 48, "est": 410, "phases": [60, 0, 0], "members": ["dung", "binh", "minh"], "dates": ["28/09 – 16/10", "19/10 – 18/12", "21/12 – 29/01"], "counts": ["3/5", "0/36", "0/10"]},
    {"key": "PAY-0980", "name": "Rewards points system migration", "plan": "2026-10-16", "forecast": "2026-10-21", "late": 2, "used": 584, "est": 540, "phases": [100, 100, 86], "members": ["binh", "chi", "dung", "minh", "tanaka"], "dates": ["01/07 – 31/07", "03/08 – 18/09", "21/09 – 16/10"], "counts": ["6/6", "55/55", "12/14"]},
]

PARENTS = [
    {"key": "PAY-1102", "summary": "[Implement_Coding] Payment flow revision"},
    {"key": "PAY-1101", "summary": "[Prepare] Payment flow revision"},
    {"key": "PAY-1103", "summary": "[Function Unit Test] Payment flow revision"},
]

# Hours logged Mon–Fri of the current week (05/10–09/10), used for the timesheet and Q&A.
WEEK = {"an": [8, 8, 4, 0, 0], "binh": [8, 7.5, 3, 0, 0], "chi": [7.5, 8, 2.5, 0, 0], "dung": [8, 6, 3, 0, 0], "minh": [8, 8, 5, 0, 0], "tanaka": [7, 7.5, 4, 0, 0]}

DEFAULT_RULE = "[{phase_name}][{function_id}] {function_name} – {step}"

SETTINGS = {
    "jira": {
        "url": "https://acme-pay.atlassian.net",
        "email": "tracker-bot@acme.vn",
        "token": "ATATT3xFfGF0••••••••",
        "interval": "15",
        "status": "ok",
        "epics": ["PAY-1100", "CRM-400", "OPS-200"],
        "lastFullSync": "07/10 08:00",
    },
    "map": {"ps": "customfield_10020", "pe": "customfield_10016", "eff": "timeoriginalestimate"},
    "rule": DEFAULT_RULE,
    "alias": {"create": "Create", "balr": "BAL Review", "fixb": "Fix comment BAL, Fix BAL", "jmr": "JM Review", "fixj": "Fix comment JM, Fix JM"},
}


def _mk_logs(c: dict, today: str) -> list[dict]:
    out, left = [], c["logged"]
    d = c["pe"] if c["pe"] < today else today
    i = 0
    while left > 0 and i < 4:
        h = min(left, left / 2 if i == 0 and left > 4 else 4)
        out.append({"date": d, "who": c["worker"], "h": h, "note": STEP_NOTES.get(c.get("sk"), "Work on task")})
        left -= h
        d = add(d, -1)
        while js_weekday(d) % 6 == 0:
            d = add(d, -1)
        i += 1
    return out


def _build_functions(cal: Calendar, today: str) -> list[dict]:
    k = 1201
    fns = []
    for fid, name, ba, start, steps in _SPEC:
        cur = start
        cells = []
        for si, s in enumerate(steps):
            st, lg, es = s[0], s[1], s[2]
            o = s[3] if len(s) > 3 else {}
            step = STEPS[si]
            who = "minh" if step["k"] == "balr" else "tanaka" if step["k"] == "jmr" else (o.get("h") or ba)
            if o.get("e"):
                ps, pe = o.get("s") or o["e"], o["e"]
            else:
                frm = add(today, 1) if st == "N" and cur <= today else cur
                ps, pe = cal.span(frm, step["dur"], who)
            cur = add(pe, 1)
            c = {
                "key": f"PAY-{k}", "fid": fid, "fname": name, "ba": ba, "si": si, "sk": step["k"],
                "stepLabel": step["label"], "isFix": bool(step.get("fix")), "isRev": bool(step.get("rev")),
                "phase": "Implement_Coding", "status": st, "logged": lg, "est": es, "assignee": who,
                "worker": who if step.get("rev") else ba, "ps": ps, "pe": pe,
                "rounds": o.get("r", 0), "held": o.get("d", 0), "prj": "PAY",
            }
            k += 1
            c["worklogs"] = _mk_logs(c, today)
            cells.append(c)
        fns.append({"id": fid, "name": name, "ba": ba, "cells": cells})
    return fns


def _build_tasks() -> list[dict]:
    tasks = []
    for key, title, phase, who, status, est, logged, ps, pe in _EXTRAS:
        tasks.append({
            "kind": "extra", "key": key, "title": title, "phase": phase, "assignee": who, "worker": who,
            "status": status, "est": est, "logged": logged, "ps": ps, "pe": pe, "rounds": 0, "held": 0, "prj": "PAY",
            "worklogs": [{"date": "2026-10-06", "who": who, "h": logged, "note": "Work on task"}] if logged else [],
        })
    for s in _STATICS:
        t = {"kind": "static", "phase": "", "rounds": 0, "held": 0, "isFix": False, "prj": "PAY", **copy.deepcopy(s)}
        t["worker"] = t.get("ba") or t["assignee"]
        t["worklogs"] = [{"date": "2026-10-05", "who": t["worker"], "h": t["logged"], "note": "Work on task"}] if t["logged"] else []
        tasks.append(t)
    for key, title, who, ps, pe, est, logged, act in _OTHER:
        act_s = act[0] if act else None
        act_e = act[1] if act and len(act) > 1 else None
        tasks.append({
            "kind": "other", "key": key, "title": title, "prj": key.split("-")[0], "assignee": who, "worker": who,
            "ps": ps, "pe": pe, "est": est, "logged": logged, "status": "C" if act_e else "W" if logged else "N",
            "phase": "", "rounds": 0, "held": 0, "actS": act_s, "actE": act_e,
            "worklogs": [{"date": act_s, "who": who, "h": logged, "note": "Work on task"}] if act_s else [],
        })
    return tasks


_PAT = [8, 8, 8, 7.5, 8, 8, 6.5, 8, 9.5, 8, 8, 0, 8, 8.5, 8, 7, 8, 10]


def _build_timesheet(state: dict, cal: Calendar, today: str) -> dict:
    """Synthesises worklog history per member/day (stands in for synced Jira worklogs)."""
    items = [c for f in state["functions"] for c in f["cells"]] + state["tasks"]
    leaves = state["leaves"]
    week_start = "2026-10-05"
    ts: dict = {}
    for mid in state["members"]:
        ts[mid] = {}
        s = "2026-09-01"
        while s <= today:
            if cal.off(s, mid):
                s = add(s, 1)
                continue
            lv = next((l for l in leaves if l["who"] == mid and l["date"] == s), None)
            if s >= week_start:
                idx = (parse(s) - parse(week_start)).days
                wk = state["week"].get(mid, [])
                h = wk[idx] if idx < len(wk) else 0
            else:
                n = int(s[8:]) + int(s[5:7]) * 31
                h = _PAT[(n * 7 + len(mid) * 3 + ord(mid[0])) % len(_PAT)]
            if lv and lv["type"] == "half":
                h = min(h, 4)
            act = [c for c in items if c["worker"] == mid and c["ps"] <= s <= c["pe"]][:3]
            ent = []
            if h:
                if not act:
                    ent.append({"key": "", "title": "Internal meeting / support", "h": h, "note": "Meeting"})
                else:
                    left = h
                    for i, c in enumerate(act):
                        x = left if i == len(act) - 1 else js_round(h / len(act) * 2) / 2
                        left -= x
                        if x > 0:
                            ent.append({"key": c["key"], "title": task_title(c), "h": x, "note": STEP_NOTES.get(c.get("sk"), "Work on task")})
            ts[mid][s] = ent
            s = add(s, 1)
    return ts


def build_initial_state(today: str = TODAY) -> dict:
    state = {
        "members": copy.deepcopy(MEMBERS),
        "config": copy.deepcopy(CONFIG),
        "holidays": copy.deepcopy(HOLIDAYS),
        "extraDays": copy.deepcopy(EXTRA_DAYS),
        "leaves": [{"id": i + 1, "who": w, "date": d, "type": t, "note": ""} for i, (w, d, t) in enumerate(_LEAVES)],
        "nextLeaveId": 100,
        "nextKey": 1501,
        "week": copy.deepcopy(WEEK),
        "settings": copy.deepcopy(SETTINGS),
    }
    cal = Calendar(state)
    state["functions"] = _build_functions(cal, today)
    state["tasks"] = _build_tasks()
    state["timesheet"] = _build_timesheet(state, cal, today)
    return state
