import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest
import respx

from tracker.adapters.jira.client import JiraCloudReader, JiraConfig
from tracker.application.ports import JiraAuthError, JiraError

FIX = Path(__file__).with_name("fixtures") / "jira"
SITE = "https://acme-pay.atlassian.net"
CFG = JiraConfig(SITE, "bot@acme.vn", "token", ("customfield_10020", "customfield_10016", "timeoriginalestimate"))


def fx(name):
    return json.loads((FIX / name).read_text("utf-8"))


def reader(sleeps=None):
    return JiraCloudReader(lambda: CFG, sleep=(sleeps.append if sleeps is not None else lambda s: None))


def search_route(mock, *, incremental=False):
    def respond(request):
        body = json.loads(request.content)
        jql = body["jql"]
        assert body["expand"] == "changelog" and "customfield_10020" in body["fields"] and "worklog" in body["fields"]
        if jql.startswith('parent in ("PAY-1100")'):
            return httpx.Response(200, json=fx("search_parents.json"))
        assert jql.startswith('parent in ("PAY-1102")')
        if incremental:
            assert 'updated >= "-' in jql
        page = "search_subtasks_page2.json" if body.get("nextPageToken") == "page-2" else "search_subtasks_page1.json"
        return httpx.Response(200, json=fx(page))

    return mock.post(f"{SITE}/rest/api/3/search/jql").mock(side_effect=respond)


@respx.mock
def test_search_walks_epic_parents_then_subtasks_across_pages():
    search_route(respx)
    respx.get(f"{SITE}/rest/api/3/issue/PAY-1213/worklog").mock(return_value=httpx.Response(200, json=fx("worklog_PAY-1213.json")))
    respx.get(f"{SITE}/rest/api/3/issue/PAY-1213/changelog").mock(return_value=httpx.Response(200, json=fx("changelog_PAY-1213.json")))
    snaps = {s.key: s for s in reader().search(["PAY-1100"], None)}
    assert list(snaps) == ["PAY-1102", "PAY-1201", "PAY-1213"]
    p, c, f = snaps["PAY-1102"], snaps["PAY-1201"], snaps["PAY-1213"]
    assert (p.parent_key, p.project, p.created, p.assignee.display_name) == ("PAY-1100", "PAY", "2026-09-10", "Hoàng Minh")
    assert (c.status_name, c.status_category, c.fields["timeoriginalestimate"], c.fields["customfield_10016"]) == ("Done", "done", 36000, "2026-09-15")
    assert [(w.date, w.hours, w.comment) for w in c.worklogs] == [("2026-09-15", 5, "Write spec"), ("2026-09-14", 5, "")]
    assert [(x.at, x.field, x.from_value, x.to_value) for x in c.changes] == [("2026-09-16", "status", "In Progress", "Done")]
    # truncated worklogs and changelog were fetched in full
    assert [w.hours for w in f.worklogs] == [3, 2.5, 2] and f.worklogs[0].comment == "plain text comment"
    assert [(x.at, x.from_value, x.to_value, x.to_user.display_name) for x in f.changes] == [
        ("2026-09-30", "5b10ac8d82e05b22cc7d4ef5", "557058:binh", "Trần Văn Bình"),
        ("2026-10-05", "557058:binh", "5b10ac8d82e05b22cc7d4ef5", "Hoàng Minh")]


@respx.mock
def test_incremental_search_filters_by_relative_updated_time():
    search_route(respx, incremental=True)
    respx.get(url__regex=rf"{SITE}/rest/api/3/issue/PAY-1213/.*").mock(side_effect=lambda r: httpx.Response(
        200, json=fx("worklog_PAY-1213.json" if r.url.path.endswith("worklog") else "changelog_PAY-1213.json")))
    since = datetime.now(timezone.utc) - timedelta(days=3)
    keys = [s.key for s in reader().search(["PAY-1100"], since)]
    assert "PAY-1102" not in keys  # parent last updated 01/10, before `since`
    assert "PAY-1201" in keys


@respx.mock
def test_rate_limit_is_retried_after_the_server_delay():
    route = respx.get(f"{SITE}/rest/api/3/myself")
    route.side_effect = [httpx.Response(429, headers={"Retry-After": "2"}), httpx.Response(200, json={"accountId": "x"})]
    sleeps: list[float] = []
    assert reader(sleeps).test_connection() is True
    assert sleeps == [2.0]


@respx.mock
def test_server_errors_back_off_then_give_up():
    respx.get(f"{SITE}/rest/api/3/issue/PAY-1").mock(return_value=httpx.Response(503))
    sleeps: list[float] = []
    with pytest.raises(JiraError, match="503"):
        reader(sleeps).get_issue("PAY-1")
    assert sleeps == [1, 2, 4, 8]


@respx.mock
def test_rejected_credentials_raise_auth_error_and_fail_connection_test():
    respx.post(f"{SITE}/rest/api/3/search/jql").mock(return_value=httpx.Response(401))
    respx.get(f"{SITE}/rest/api/3/myself").mock(return_value=httpx.Response(401))
    with pytest.raises(JiraAuthError):
        list(reader().search(["PAY-1100"], None))
    assert reader().test_connection() is False


@respx.mock
def test_get_epic_only_returns_epics():
    respx.get(f"{SITE}/rest/api/3/issue/PAY-1100").mock(return_value=httpx.Response(200, json=fx("epic_PAY-1100.json")))
    respx.get(f"{SITE}/rest/api/3/issue/PAY-1201").mock(return_value=httpx.Response(200, json=fx("story_PAY-1201.json")))
    respx.get(f"{SITE}/rest/api/3/issue/PAY-9").mock(return_value=httpx.Response(404))
    e = reader().get_epic("PAY-1100")
    assert (e.key, e.project, e.summary, e.due_date) == ("PAY-1100", "PAY", "Payment flow revision", "2026-11-20")
    assert reader().get_epic("PAY-1201") is None
    assert reader().get_epic("PAY-9") is None


@respx.mock
def test_requests_use_basic_auth():
    route = respx.get(f"{SITE}/rest/api/3/myself").mock(return_value=httpx.Response(200, json={}))
    reader().test_connection()
    assert route.calls[0].request.headers["authorization"].startswith("Basic ")


@respx.mock
def test_list_fields_reads_the_field_catalog():
    respx.get(f"{SITE}/rest/api/3/field").mock(return_value=httpx.Response(200, json=[
        {"id": "customfield_10020", "name": "Planned Start", "schema": {"type": "date"}},
        {"id": "timeoriginalestimate", "name": "Original estimate", "schema": {"type": "number"}},
        {"id": "summary", "name": "Summary", "schema": {"type": "string"}},
        {"id": "issuekey", "name": "Key"},
    ]))
    fields = {f.id: (f.name, f.type) for f in reader().list_fields()}
    assert fields["customfield_10020"] == ("Planned Start", "date") and fields["issuekey"] == ("Key", "")
