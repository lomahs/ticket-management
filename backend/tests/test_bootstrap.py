from fastapi.testclient import TestClient

from tracker.adapters.api.app import create_app
from tracker.adapters.scheduler import start
from tracker.bootstrap import build_demo, build_jira


def test_demo_mode_boots_with_synced_data(tmp_path):
    deps = build_demo(tmp_path / "demo" / "tracker.db")
    c = TestClient(create_app(deps))
    m = c.get("/api/v1/meta").json()
    assert m["mode"] == "demo" and m["capabilities"]["jira_write"] and m["sync"]["status"] == "ok"
    assert len(c.get("/api/v1/anken").json()) == 6


def test_jira_mode_without_configuration_is_read_only_and_reports_sync_problems(tmp_path):
    deps = build_jira(f"sqlite:///{tmp_path / 'tracker.db'}", None)
    with TestClient(create_app(deps, background=start)) as c:  # lifespan starts and stops the scheduler
        m = c.get("/api/v1/meta").json()
        assert m["mode"] == "jira" and m["capabilities"] == {"jira_write": False} and m["sync"]["status"] is None
        assert c.post("/api/v1/sync", json={"full": True}).json()["status"] == "ok"  # nothing tracked yet
        r = c.post("/api/v1/settings/epics", json={"key": "PAY-1"})
        assert r.status_code == 502 and r.json()["code"] == "jira_unavailable"
        assert c.get("/api/v1/settings/jira").json()["status"] is None

