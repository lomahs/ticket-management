"""Domain rules shared by the API: working-day calendar, task flags and the Q&A bot."""

import re

from .dates import add, days_between, dm, f1, js_weekday, wd

STEPS = [
    {"k": "create", "label": "Create", "dur": 2},
    {"k": "balr", "label": "BAL Review", "dur": 1, "rev": True},
    {"k": "fixb", "label": "Fix comment BAL", "dur": 1, "fix": True},
    {"k": "jmr", "label": "JM Review", "dur": 2, "rev": True},
    {"k": "fixj", "label": "Fix comment JM", "dur": 1, "fix": True},
]

LEAVE_TYPES = {
    "full": {"label": "All day", "ab": "1", "c": "oklch(0.6 0.13 250)", "full": True},
    "half": {"label": "Half day", "ab": "½", "c": "oklch(0.6 0.13 250)", "half": True},
    "fullTent": {"label": "All day (tentative)", "ab": "1?", "c": "oklch(0.62 0.13 310)", "full": True, "tent": True},
    "halfTent": {"label": "Half day (tentative)", "ab": "½?", "c": "oklch(0.62 0.13 310)", "half": True, "tent": True},
    "holiday": {"label": "Holiday", "ab": "H", "c": "oklch(0.6 0.02 260)"},
}

STATUS_LABEL = {"N": "NYS", "W": "WIP", "R": "Resolved", "C": "Closed"}


def task_title(c: dict) -> str:
    if c.get("fid"):
        return f"[{c['phase']}][{c['fid']}] {c['fname']} – {c['stepLabel']}"
    return c.get("title", "")


class Calendar:
    """Answers "is this a working day for this member?" from config, holidays, extra days and leave."""

    def __init__(self, state: dict):
        self.s = state

    def full_leave(self, who: str, s: str) -> str | None:
        lt = LEAVE_TYPES
        for l in self.s["leaves"]:
            if l["who"] == who and l["date"] == s and lt[l["type"]].get("full"):
                return f"{self.s['members'][who]['name']} · {lt[l['type']]['label']}"
        return None

    def visible(self, s: str) -> bool:
        return js_weekday(s) in self.s["config"]["days"] or s in self.s["extraDays"]

    def off(self, s: str, who: str) -> str | None:
        """None for a working day, 'we' for a non-working weekday, else the reason (holiday / leave)."""
        if js_weekday(s) not in self.s["config"]["days"] and s not in self.s["extraDays"]:
            return "we"
        h = self.s["holidays"].get(s)
        m = self.s["members"].get(who)
        if h and m and m["c"] in h[0]:
            return h[1]
        return self.full_leave(who, s)

    def next_work(self, s: str, who: str, skipped: list | None = None) -> str:
        while True:
            r = self.off(s, who)
            if not r:
                return s
            if r != "we" and skipped is not None:
                skipped.append(f"{dm(s)} {r}")
            s = add(s, 1)

    def span(self, s: str, n: int, who: str, skipped: list | None = None) -> tuple[str, str]:
        st = self.next_work(s, who, skipped)
        e = st
        for _ in range(1, n):
            e = self.next_work(add(e, 1), who, skipped)
        return st, e


def all_cells(state: dict) -> list[dict]:
    return [c for f in state["functions"] for c in f["cells"]]


def all_items(state: dict) -> list[dict]:
    return all_cells(state) + state["tasks"]


def find_item(state: dict, key: str) -> dict | None:
    return next((c for c in all_items(state) if c["key"] == key), None)


def tone(c: dict, today: str) -> str:
    if c["status"] == "N":
        return "nys"
    if c["status"] == "R":
        return "res"
    if c["status"] == "C":
        return "clo"
    if c["pe"] < today:
        return "delay"
    ds = sorted(w["date"] for w in c["worklogs"])
    if ds and ds[0] > c["ps"]:
        return "latestart"
    if ds and ds[0] < c["ps"]:
        return "early"
    return "ontime"


def _flag(c: dict, today: str, soon: int):
    active = c["status"] == "W" or (c["status"] == "N" and c["isCur"])
    c["late"] = active and c["pe"] < today
    c["lateDays"] = wd(c["pe"], today) if c["late"] else 0
    c["soon"] = (not c["late"]) and active and c["pe"] >= today and wd(today, c["pe"]) <= soon
    c["over"] = c["logged"] > c["est"]
    c["tone"] = tone(c, today)


def annotate(state: dict, today: str):
    """Recomputes derived flags (current step, late, over estimate, tone) in place."""
    soon = state["config"]["soon"]
    for f in state["functions"]:
        cur = next((i for i, c in enumerate(f["cells"]) if c["status"] != "C"), 5)
        f["cur"] = cur
        for i, c in enumerate(f["cells"]):
            c["isCur"] = i == cur
            c["order"] = c["status"] != "N" and any(x["status"] in ("N", "W") for x in f["cells"][:i])
            _flag(c, today, soon)
    for c in state["tasks"]:
        c["isCur"] = c["status"] != "C"
        _flag(c, today, soon)


def answer(state: dict, q: str) -> dict:
    """Rule-based answers for the Task Q&A panel. Expects an annotated state."""
    t = q.lower()
    cells = all_cells(state)
    keys = lambda l: [c["key"] for c in l]  # noqa: E731
    if "late" in t:
        l = sorted((c for c in cells if c["late"]), key=lambda c: -c["lateDays"])
        if not l:
            return {"text": "No sub-tasks are past their plan.", "chips": []}
        return {"text": f"{len(l)} sub-tasks are past their plan, across {len({c['fid'] for c in l})} functions. "
                        f"Longest delay: {l[0]['fid']} ({l[0]['lateDays']} working days).", "chips": keys(l)}
    if "minh" in t or "bal" in t:
        l = [c for c in cells if c["assignee"] == "minh" and c["isCur"] and c["status"] != "C"]
        longest = max((c["held"] for c in l), default=0)
        return {"text": f"Minh is holding {len(l)} tickets. BAL Review is the most backed-up column in the matrix; "
                        f"the longest has been held {longest} days.", "chips": keys(l)}
    if "round" in t or "ping" in t:
        l = sorted((c for c in cells if c["rounds"] >= 2), key=lambda c: -c["rounds"])
        return {"text": f"{sum(1 for c in l if c['rounds'] >= 3)} Fix comment tickets have gone 3 rounds or more. Sorted by rounds:",
                "chips": keys(l)}
    if "jm" in t or "tanaka" in t or "client" in t:
        l = [c for c in cells if c["assignee"] == "tanaka" and c["status"] != "C" and c["held"] > 0]
        longest = max((c["held"] for c in l), default=0)
        return {"text": f"{len(l)} tickets are with Tanaka (JM). Longest wait: {longest} days.", "chips": keys(l)}
    if "log" in t or "hour" in t:
        w = state["week"].get("binh", [0] * 5)
        l = [c for c in cells if c["worker"] == "binh" and c["logged"] > 0 and c["status"] != "C"]
        return {"text": f"Bình has logged {f1(sum(w))}h / 40h this week (05/10–09/10), {f1(w[2])}h today. "
                        "Most hours went to the F03 Fix BAL ticket, which is over estimate.", "chips": keys(l)}
    return {"text": "I did not understand that. Try asking about late tasks, review tickets, rounds, waiting on JM or logged hours.",
            "chips": []}


_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def validate_leave_rows(state: dict, rows: list[list]) -> list[dict]:
    """Checks imported leave rows (Member, From, To, Type, Note). Row numbers start at 2 (after the header)."""
    lt = LEAVE_TYPES
    out = []
    for i, r in enumerate(rows):
        r = [("" if v is None else str(v)) for v in list(r) + [""] * 5][:5]
        mn, f, t, ty, note = r
        k0 = mn.strip().lower()
        mid = next((k for k, m in state["members"].items() if m["name"].lower() == k0 or k == k0), None)
        t0 = ty.strip().lower()
        tk = next((k for k, v in lt.items() if k != "holiday" and (v["label"].lower() == t0 or k.lower() == t0)), None)
        to = t or f
        err = ""
        if not mid:
            err = f'Unknown member "{mn}"'
        elif not _DATE_RE.match(f) or not _DATE_RE.match(to):
            err = "Invalid date format, use YYYY-MM-DD"
        elif to < f:
            err = "To date is before From date"
        elif not tk:
            err = f'Unknown leave type "{ty}"'
        else:
            for s in days_between(f, to):
                if any(l["who"] == mid and l["date"] == s for l in state["leaves"]):
                    err = "Overlaps existing leave on " + dm(s)
                    break
        out.append({"n": i + 2, "member": mn, "from": f, "to": to, "type": ty, "note": note, "id": mid, "tk": tk, "err": err})
    return out
