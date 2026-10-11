"""Jira Cloud REST API v3 reader (httpx).

Structure read: tracked epic → parent tickets (one per phase) → sub-tasks. Search uses the enhanced JQL endpoint
(POST /rest/api/3/search/jql, nextPageToken pagination). Worklogs and changelogs embedded in search results can be
truncated; those are completed from the per-issue endpoints.
"""

import math
import time
from collections.abc import Callable, Iterator, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import httpx

from tracker.application.dto import ChangeSnapshot, EpicSnapshot, IssueSnapshot, JiraField, UserSnapshot, WorklogSnapshot
from tracker.application.ports import JiraAuthError, JiraError

BASE_FIELDS = ("summary", "status", "assignee", "parent", "project", "created", "updated", "worklog")
MAX_ATTEMPTS = 5
PARENT_CHUNK = 50


@dataclass(frozen=True)
class JiraConfig:
    url: str
    email: str
    token: str
    fields: tuple[str, ...]  # mapped field ids (planned start/end, effort)


def _quote(keys: Sequence[str]) -> str:
    return ", ".join(f'"{k}"' for k in keys)


def _adf_text(node: Any) -> str:
    """Plain text of an Atlassian Document Format value (or a plain string)."""
    if node is None:
        return ""
    if isinstance(node, str):
        return node
    if node.get("type") == "text":
        return node.get("text", "")
    parts = [_adf_text(c) for c in node.get("content", [])]
    sep = "\n" if node.get("type") == "doc" else ""
    return sep.join(p for p in parts if p)


def _account(u: dict) -> UserSnapshot:
    return UserSnapshot(u["accountId"], u.get("displayName", ""), u.get("emailAddress", ""))


def _user(u: dict | None) -> UserSnapshot | None:
    return _account(u) if u else None


def _dt(s: str) -> datetime:
    return datetime.fromisoformat(s).astimezone(timezone.utc)


class JiraCloudReader:
    def __init__(self, config: Callable[[], JiraConfig], *, sleep: Callable[[float], None] = time.sleep,
                 timeout: float = 20.0, transport: httpx.BaseTransport | None = None):
        self._config, self._sleep, self._timeout, self._transport = config, sleep, timeout, transport

    # --- HTTP -------------------------------------------------------------------------------------------------

    def _client(self) -> tuple[httpx.Client, JiraConfig]:
        cfg = self._config()
        if not cfg.url:
            raise JiraError("Jira site URL is not configured")
        c = httpx.Client(base_url=cfg.url.rstrip("/"), auth=(cfg.email, cfg.token), timeout=self._timeout,
                         headers={"Accept": "application/json"}, transport=self._transport)
        return c, cfg

    def _call(self, client: httpx.Client, method: str, path: str, **kw) -> dict | None:
        for attempt in range(MAX_ATTEMPTS):
            try:
                r = client.request(method, path, **kw)
            except httpx.HTTPError as e:
                if attempt == MAX_ATTEMPTS - 1:
                    raise JiraError(f"Cannot reach Jira: {e}") from e
                self._sleep(2 ** attempt)
                continue
            if r.status_code in (401, 403):
                raise JiraAuthError(f"{r.status_code} {r.reason_phrase}: check the account email and API token")
            if r.status_code == 404:
                return None
            if r.status_code == 429 or r.status_code >= 500:
                if attempt == MAX_ATTEMPTS - 1:
                    raise JiraError(f"{r.status_code} {r.reason_phrase} from Jira after {MAX_ATTEMPTS} attempts")
                retry_after = r.headers.get("Retry-After")
                self._sleep(float(retry_after) if retry_after and retry_after.replace(".", "").isdigit() else 2 ** attempt)
                continue
            if r.status_code >= 400:
                raise JiraError(f"{r.status_code} {r.reason_phrase}: {r.text[:300]}")
            return r.json()
        raise JiraError("unreachable")

    # --- JiraReader ---------------------------------------------------------------------------------------------

    def _search(self, client: httpx.Client, cfg: JiraConfig, jql: str) -> Iterator[dict]:
        token = None
        while True:
            body = {"jql": jql, "fields": list(BASE_FIELDS + cfg.fields), "expand": "changelog", "maxResults": 100}
            if token:
                body["nextPageToken"] = token
            page = self._call(client, "POST", "/rest/api/3/search/jql", json=body) or {}
            yield from page.get("issues", [])
            token = page.get("nextPageToken")
            if page.get("isLast", True) or not token:
                return

    def search(self, epic_keys: Sequence[str], updated_since: datetime | None) -> Iterator[IssueSnapshot]:
        client, cfg = self._client()
        with client:
            parents = list(self._search(client, cfg, f"parent in ({_quote(epic_keys)}) ORDER BY key"))
            for p in parents:
                if updated_since is None or _dt(p["fields"]["updated"]) >= updated_since:
                    yield self._snapshot(client, cfg, p)
            keys = [p["key"] for p in parents]
            recent = ""
            if updated_since is not None:
                minutes = math.ceil((datetime.now(timezone.utc) - updated_since).total_seconds() / 60)
                recent = f' AND updated >= "-{max(1, minutes)}m"'  # relative: independent of the account's time zone
            for i in range(0, len(keys), PARENT_CHUNK):
                jql = f"parent in ({_quote(keys[i:i + PARENT_CHUNK])}){recent} ORDER BY key"
                for issue in self._search(client, cfg, jql):
                    yield self._snapshot(client, cfg, issue)

    def get_epic(self, key: str) -> EpicSnapshot | None:
        client, _ = self._client()
        with client:
            data = self._call(client, "GET", f"/rest/api/3/issue/{key}", params={"fields": "summary,issuetype,project,duedate"})
        if not data:
            return None
        f = data["fields"]
        it = f.get("issuetype") or {}
        if it.get("name") != "Epic" and it.get("hierarchyLevel") != 1:
            return None
        return EpicSnapshot(data["key"], f["project"]["key"], f.get("summary", ""), f.get("duedate"))

    def get_issue(self, key: str) -> IssueSnapshot | None:
        client, cfg = self._client()
        with client:
            data = self._call(client, "GET", f"/rest/api/3/issue/{key}",
                              params={"fields": ",".join(BASE_FIELDS + cfg.fields), "expand": "changelog"})
            return self._snapshot(client, cfg, data) if data else None

    def list_fields(self) -> list[JiraField]:
        client, _ = self._client()
        with client:
            data = self._call(client, "GET", "/rest/api/3/field") or []
        return [JiraField(f["id"], f.get("name", f["id"]), (f.get("schema") or {}).get("type", "")) for f in data]

    def test_connection(self) -> bool:
        try:
            client, _ = self._client()
            with client:
                return self._call(client, "GET", "/rest/api/3/myself") is not None
        except JiraError:
            return False

    # --- mapping -------------------------------------------------------------------------------------------------

    def _worklogs(self, client: httpx.Client, key: str, embedded: dict) -> list[dict]:
        logs = embedded.get("worklogs", [])
        if embedded.get("total", len(logs)) <= len(logs):
            return logs
        out, start = [], 0
        while True:
            page = self._call(client, "GET", f"/rest/api/3/issue/{key}/worklog", params={"startAt": start, "maxResults": 5000}) or {}
            out += page.get("worklogs", [])
            start = len(out)
            if start >= page.get("total", 0) or not page.get("worklogs"):
                return out

    def _histories(self, client: httpx.Client, key: str, embedded: dict) -> list[dict]:
        hist = embedded.get("histories", [])
        if embedded.get("total", len(hist)) <= len(hist):
            return hist
        out, start = [], 0
        while True:
            page = self._call(client, "GET", f"/rest/api/3/issue/{key}/changelog", params={"startAt": start, "maxResults": 100}) or {}
            out += page.get("values", [])
            start = len(out)
            if page.get("isLast", True) or not page.get("values"):
                return out

    def _snapshot(self, client: httpx.Client, cfg: JiraConfig, issue: dict) -> IssueSnapshot:
        key, f = issue["key"], issue["fields"]
        logs = [WorklogSnapshot(_account(w["author"]), w["started"][:10], w.get("timeSpentSeconds", 0) / 3600, _adf_text(w.get("comment")))
                for w in self._worklogs(client, key, f.get("worklog") or {})]
        changes = []
        for h in self._histories(client, key, issue.get("changelog") or {}):
            at = h["created"][:10]
            for it in h.get("items", []):
                if it.get("field") == "assignee":
                    frm = UserSnapshot(it["from"], it.get("fromString") or "") if it.get("from") else None
                    to = UserSnapshot(it["to"], it.get("toString") or "") if it.get("to") else None
                    changes.append(ChangeSnapshot(at, "assignee", it.get("from"), it.get("to"), frm, to))
                elif it.get("field") == "status":
                    changes.append(ChangeSnapshot(at, "status", it.get("fromString"), it.get("toString")))
        status = f.get("status") or {}
        return IssueSnapshot(
            key=key, project=(f.get("project") or {}).get("key", key.split("-")[0]), summary=f.get("summary", ""),
            status_name=status.get("name", ""), status_category=(status.get("statusCategory") or {}).get("key", "indeterminate"),
            assignee=_user(f.get("assignee")), parent_key=(f.get("parent") or {}).get("key"),
            created=(f.get("created") or "")[:10], updated=_dt(f["updated"]).isoformat() if f.get("updated") else "",
            fields={fid: f.get(fid) for fid in cfg.fields}, worklogs=tuple(logs), changes=tuple(changes),
        )
