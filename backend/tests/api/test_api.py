import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from tests.application.demo import demo_deps
from tracker.adapters.api.app import create_app


@pytest.fixture(scope="module")
def client(tmp_path_factory):
    return TestClient(create_app(demo_deps(tmp_path_factory.mktemp("api"))))


@pytest.fixture()
def fresh(tmp_path):
    return TestClient(create_app(demo_deps(tmp_path)))


def problem(r, status, code):
    assert r.status_code == status, r.text
    assert r.headers["content-type"].startswith("application/problem+json")
    body = r.json()
    assert body["code"] == code
    return body


# --- reads ------------------------------------------------------------------------------------------------------

def test_meta_and_members(client):
    m = client.get("/api/v1/meta").json()
    assert m["mode"] == "demo" and m["today"] == "2026-10-07" and m["capabilities"] == {"jira_write": True}
    assert m["sync"]["status"] == "ok"
    assert client.get("/api/v1/members").json()[0]["id"] == "an"


def test_my_work(client):
    w = client.get("/api/v1/my-work", params={"member": "binh", "backlog": "week"}).json()
    assert w["delay"][0]["key"] == "PAY-1012" and w["backlog"][0]["kind"] == "overdue"
    problem(client.get("/api/v1/my-work", params={"member": "zz"}), 404, "not_found")
    problem(client.get("/api/v1/my-work", params={"member": "binh", "backlog": "year"}), 422, "invalid")


def test_anken_overview_detail_matrix(client):
    assert [a["key"] for a in client.get("/api/v1/anken").json()][0] == "PAY-1100"
    d = client.get("/api/v1/anken/PAY-1100").json()
    assert d["card"]["late_functions"] == 6 and d["attention"][1]["kind"] == "stuck_resolved"
    m = client.get("/api/v1/anken/PAY-1100/matrix").json()
    assert m["stats"]["logged_h"] == 148 and len(m["functions"]) == 12 and m["functions"][0]["cells"][0]["tone"] == "clo"
    problem(client.get("/api/v1/anken/NOPE-1/matrix"), 404, "not_found")


def test_function_preview_validates_body(client):
    body = {"parent_key": "PAY-1102", "name": "Installment", "ba": "an", "bal": "minh", "jm": "tanaka", "create_hours": 8,
            "overrides": [None, None, None, None, None]}
    p = client.post("/api/v1/anken/PAY-1100/functions/preview", json=body).json()
    assert p["function_id"] == "F13" and p["steps"][0]["start"] == "2026-10-08"
    err = problem(client.post("/api/v1/anken/PAY-1100/functions/preview", json={**body, "create_hours": -1}), 422, "invalid")
    assert err["errors"][0]["field"] == "create_hours"


def test_planning_effort_calendar(client):
    p = client.get("/api/v1/planning", params={"start": "2026-10-05", "weeks": 2}).json()
    assert p["start"] == "2026-10-05" and len(p["capacity"]) == 6
    pv = client.post("/api/v1/planning/preview", json={"start": "2026-10-05", "changes": [{"key": "PAY-1262", "start": "2026-10-11", "member_id": "binh"}]}).json()
    assert pv["changes"][0]["day_off"] == [["2026-10-11", "weekend"]]
    ts = client.get("/api/v1/effort/timesheet", params={"from": "2026-10-05", "to": "2026-10-11"}).json()
    assert len(ts["days"]) == 6
    assert client.get("/api/v1/effort/timesheet/binh/2026-10-06").json()["entries"]
    assert client.get("/api/v1/effort/analysis").json()["epic_key"] == "PAY-1100"
    cal = client.get("/api/v1/calendar", params={"month": "2026-10"}).json()
    assert cal["rows"][0]["member_id"] == "an"


def test_ticket_and_qa(client):
    t = client.get("/api/v1/tickets/PAY-1213").json()
    assert t["card"]["rounds"] == 3 and t["warnings"][0]["kind"] == "late"
    a = client.post("/api/v1/qa", json={"q": "Which tickets ping-pong for many rounds?"}).json()
    assert "rounds" in a["text"]


def test_settings_reads(client):
    j = client.get("/api/v1/settings/jira").json()
    assert "token" not in j and j["token_masked"].endswith("••••••••")
    assert client.get("/api/v1/settings/field-mapping").json()["catalog"]
    assert client.get("/api/v1/settings/status-mapping").json()
    nr = client.get("/api/v1/settings/name-rules").json()
    assert nr["aliases"]["create"] == "Create"
    assert client.get("/api/v1/holidays").json()[0]["date"] == "2026-09-01"
    assert client.get("/api/v1/extra-days").json() == {"2026-10-10": "Release support · go-live weekend"}
    assert client.get("/api/v1/settings/working-hours").json()["days"] == [1, 2, 3, 4, 5]
    assert client.get("/api/v1/settings/alerts").json()["soon_days"] == 2


# --- writes -----------------------------------------------------------------------------------------------------

def test_ticket_patch_and_planning_push(fresh):
    t = fresh.patch("/api/v1/tickets/PAY-1213", json={"status": "R"}).json()
    assert t["card"]["status"] == "R"
    r = fresh.post("/api/v1/planning/push", json={"changes": [{"key": "PAY-1262", "start": "2026-10-12", "member_id": "binh"}]})
    assert r.json() == {"keys": ["PAY-1262"]}


def test_create_function(fresh):
    body = {"parent_key": "PAY-1102", "name": "Installment", "ba": "an", "bal": "minh", "jm": "tanaka", "create_hours": 8,
            "overrides": [None] * 5}
    assert fresh.post("/api/v1/anken/PAY-1100/functions", json=body).json()["keys"][0] == "PAY-1501"


def test_leaves_crud_import_export(fresh):
    r = fresh.post("/api/v1/leaves", json={"member_id": "an", "type": "full", "date_from": "2026-11-02", "date_to": "2026-11-06", "note": ""})
    assert r.json() == {"days": 5}
    problem(fresh.post("/api/v1/leaves", json={"member_id": "an", "type": "full", "date_from": "2026-11-06", "date_to": "2026-11-02"}), 422, "invalid")
    pv = fresh.post("/api/v1/leaves/import/preview", files={"file": ("x.csv", "﻿Member,From,To,Type,Note\nChi,2026-12-01,2026-12-02,Half day,\n".encode(), "text/csv")}).json()
    assert pv["file"] == "x.csv" and pv["rows"][0]["error"] == "" and pv["working_days"] == 2
    sample = fresh.post("/api/v1/leaves/import/preview", params={"sample": True}).json()
    assert sample["file"] == "leave-november.xlsx" and len(sample["rows"]) == 10
    res = fresh.post("/api/v1/leaves/import", json={"rows": [["Chi", "2026-12-01", "2026-12-02", "Half day", ""]]}).json()
    assert res == {"imported": 2, "month": "2026-12"}
    x = fresh.get("/api/v1/leaves/export", params={"month": "2026-12"})
    assert x.headers["content-type"].startswith("application/vnd.ms-excel") and "Lê Minh Chi" in x.text
    assert fresh.get("/api/v1/leaves/template").text.startswith("﻿Member")
    problem(fresh.post("/api/v1/leaves/import/preview", files={"file": ("x.pdf", b"%PDF", "application/pdf")}), 422, "invalid")


def test_settings_writes(fresh):
    assert fresh.post("/api/v1/settings/jira/test", json={"url": "https://x.example.com", "email": "a@b", "token": "short"}).json() == {"status": "fail"}
    problem(fresh.post("/api/v1/settings/epics", json={"key": "PAY-1100"}), 409, "conflict")
    assert "PAY-1500" in [e["key"] for e in fresh.post("/api/v1/settings/epics", json={"key": "PAY-1500"}).json()["epics"]]
    problem(fresh.post("/api/v1/extra-days", json={"date": "2026-10-07", "note": ""}), 422, "invalid")
    assert fresh.patch("/api/v1/members/an", json={"role": "BAL"}).json()["role"] == "BAL"
    assert fresh.put("/api/v1/settings/alerts", json={"soon_days": 3, "effort_warn_pct": 80, "held_days": 3, "review_rounds": 3,
                                                       "capacity_pct": 100}).json()["soon_days"] == 3
    pv = fresh.post("/api/v1/settings/name-rules/preview", json={"rule": "{step}", "sample": "Create"}).json()
    assert pv["sample_matches"] and pv["step"] == "create"


def test_sync_endpoint(fresh):
    s = fresh.post("/api/v1/sync", json={"full": False}).json()
    assert s["status"] == "ok"


# --- jira mode without writer ------------------------------------------------------------------------------------

def test_writes_are_disabled_without_jira_writer(tmp_path):
    c = TestClient(create_app(demo_deps(tmp_path, writable=False)))
    assert c.get("/api/v1/meta").json()["capabilities"] == {"jira_write": False}
    problem(c.patch("/api/v1/tickets/PAY-1213", json={"status": "R"}), 403, "capability_disabled")
    problem(c.post("/api/v1/planning/push", json={"changes": []}), 403, "capability_disabled")


# --- contract with the frontend ------------------------------------------------------------------------------------

def test_openapi_file_matches_the_app(client):
    path = Path(__file__).resolve().parents[3] / "frontend" / "src" / "shared" / "api" / "openapi.json"
    assert path.exists(), "run: npm run gen:api (in frontend/)"
    assert json.loads(path.read_text("utf-8")) == json.loads(json.dumps(client.app.openapi())), "OpenAPI changed: run npm run gen:api"


def test_import_rejects_oversized_files(fresh):
    big = b"Member,From,To,Type,Note\n" + b"x" * (5 * 1024 * 1024 + 1)
    err = problem(fresh.post("/api/v1/leaves/import/preview", files={"file": ("big.csv", big, "text/csv")}), 422, "invalid")
    assert "5 MB" in err["detail"]
