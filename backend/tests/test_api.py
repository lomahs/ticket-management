import importlib

import pytest
from fastapi.testclient import TestClient


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("TRACKER_DATA", str(tmp_path / "store.json"))
    import app.main as main

    importlib.reload(main)
    return TestClient(main.app)


def cell(snap, key):
    return next(c for f in snap["functions"] for c in f["cells"] if c["key"] == key)


def test_state_is_seeded_and_annotated(client):
    s = client.get("/api/state").json()
    assert s["today"] == "2026-10-07"
    assert len(s["functions"]) == 12 and s["nextFid"] == "F13"
    f03 = s["functions"][2]
    assert f03["cur"] == 2
    assert f03["cells"][2]["late"] and f03["cells"][2]["tone"] == "delay"
    # Tanaka's JM review skips the JP holidays 21–23/09
    assert cell(s, "PAY-1204")["pe"] == "2026-09-24"
    assert "token" not in s["settings"]["jira"]


def test_ticket_patch_and_plan_push(client):
    s = client.patch("/api/tickets/PAY-1213", json={"status": "R"}).json()
    assert cell(s, "PAY-1213")["status"] == "R" and not cell(s, "PAY-1213")["late"]
    s = client.post("/api/plan/push", json={"changes": [{"key": "PAY-1214", "ps": "2026-10-12", "pe": "2026-10-13", "who": "an"}]}).json()
    c = cell(s, "PAY-1214")
    assert (c["ps"], c["pe"], c["assignee"]) == ("2026-10-12", "2026-10-13", "an")
    assert client.patch("/api/tickets/NOPE-1", json={"status": "R"}).status_code == 404


def test_create_function(client):
    rows = [{"ps": "2026-10-08", "pe": "2026-10-09", "est": 8, "who": "an"}] + [
        {"ps": "2026-10-12", "pe": "2026-10-12", "est": 2, "who": w} for w in ("minh", "an", "tanaka", "an")]
    body = {"parent": "PAY-1102", "phase": "Implement_Coding", "fid": "F13", "name": "Installment payment", "ba": "an", "rows": rows}
    s = client.post("/api/functions", json=body).json()
    assert s["functions"][-1]["id"] == "F13" and s["nextFid"] == "F14"
    assert [c["key"] for c in s["functions"][-1]["cells"]][0] == "PAY-1501"
    assert client.post("/api/functions", json=body).status_code == 409


def test_leave_crud_and_import(client):
    s = client.post("/api/leaves", json={"who": "an", "type": "full", "from": "2026-11-02", "to": "2026-11-08", "note": "trip"}).json()
    mine = [l for l in s["leaves"] if l["who"] == "an" and l["note"] == "trip"]
    assert len(mine) == 5  # weekends skipped
    s = client.delete(f"/api/leaves/{mine[0]['id']}").json()
    assert len([l for l in s["leaves"] if l["note"] == "trip"]) == 4

    prev = client.get("/api/leaves/import/sample").json()
    errs = {r["n"]: r["err"] for r in prev["rows"]}
    assert errs[4] == "To date is before From date"
    assert errs[5].startswith("Unknown member")
    assert errs[9].startswith("Overlaps existing leave")
    assert errs[11].startswith("Unknown leave type")
    raw = [[r["member"], r["from"], r["to"], r["type"], r["note"]] for r in prev["rows"]]
    res = client.post("/api/leaves/import", json={"file": prev["file"], "rows": raw}).json()
    # An's 05–06/11 row now overlaps the leave added above, so only Bình 1 + Dũng 1 + Minh 3 + Tanaka 1 remain
    assert res["imported"] == 6 and res["month"] == "2026-11"


def test_csv_import_preview(client):
    csv = "﻿Member,From,To,Type,Note\nChi,2026-12-01,2026-12-02,Half day,\n".encode()
    r = client.post("/api/leaves/import/preview", files={"file": ("x.csv", csv, "text/csv")}).json()
    assert r["rows"][0]["err"] == "" and r["rows"][0]["tk"] == "half"


def test_settings(client):
    assert client.post("/api/extra-days", json={"date": "2026-10-07"}).json()["detail"] == "07/10 is already a working day"
    s = client.post("/api/extra-days", json={"date": "2026-10-17", "note": "Cutover"}).json()
    assert s["extraDays"]["2026-10-17"] == "Cutover"
    assert client.post("/api/settings/epics", json={"key": "PAY-9999"}).status_code == 404
    s = client.post("/api/settings/epics", json={"key": "pay-1150"}).json()
    assert "PAY-1150" in s["settings"]["jira"]["epics"]
    assert client.post("/api/settings/jira/test", json={"url": "https://x.example.com"}).json()["status"] == "fail"
    s = client.patch("/api/config", json={"days": [1, 2, 3, 4, 5, 6]}).json()
    assert s["config"]["days"] == [1, 2, 3, 4, 5, 6]


def test_chat(client):
    a = client.post("/api/chat", json={"q": "Which functions are late?"}).json()
    assert a["text"].startswith("6 sub-tasks are past their plan") and len(a["chips"]) == 6
    assert "did not understand" in client.post("/api/chat", json={"q": "weather?"}).json()["text"]


def test_state_persists(client, tmp_path):
    client.patch("/api/members/an", json={"role": "BAL"})
    import app.main as main

    importlib.reload(main)
    assert TestClient(main.app).get("/api/state").json()["members"]["an"]["role"] == "BAL"
