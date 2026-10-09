"""Records anonymised Jira responses for one epic into tests/adapters/fixtures/jira/recorded/.

Reads a REAL Jira site, so run it only on purpose:
  TRACKER_JIRA_URL=https://<site>.atlassian.net TRACKER_JIRA_EMAIL=... TRACKER_JIRA_TOKEN=... \
  TRACKER_JIRA_EPIC=PAY-1100 TRACKER_JIRA_FIELDS=customfield_10020,customfield_10016,timeoriginalestimate \
  .venv/bin/python scripts/record_jira_fixtures.py
Display names, e-mails and account ids are replaced; summaries and dates are kept.
"""

import hashlib
import json
import os
from pathlib import Path

import httpx

OUT = Path(__file__).resolve().parents[1] / "tests" / "adapters" / "fixtures" / "jira" / "recorded"


def anonymise(obj, names: dict):
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k == "accountId" or (k in ("from", "to") and obj.get("field") == "assignee" and v):
                out[k] = "acc-" + hashlib.sha1(str(v).encode()).hexdigest()[:10]
            elif k in ("displayName", "fromString", "toString") and (k == "displayName" or obj.get("field") == "assignee") and v:
                out[k] = names.setdefault(v, f"User {len(names) + 1}")
            elif k == "emailAddress":
                out[k] = ""
            else:
                out[k] = anonymise(v, names)
        return out
    if isinstance(obj, list):
        return [anonymise(v, names) for v in obj]
    return obj


def main():
    url, epic = os.environ["TRACKER_JIRA_URL"].rstrip("/"), os.environ["TRACKER_JIRA_EPIC"]
    fields = os.environ.get("TRACKER_JIRA_FIELDS", "customfield_10020,customfield_10016,timeoriginalestimate").split(",")
    auth = (os.environ["TRACKER_JIRA_EMAIL"], os.environ["TRACKER_JIRA_TOKEN"])
    base = ["summary", "status", "assignee", "parent", "project", "created", "updated", "worklog"]
    names: dict = {}
    OUT.mkdir(parents=True, exist_ok=True)
    with httpx.Client(base_url=url, auth=auth, timeout=30) as c:
        def search(jql):
            r = c.post("/rest/api/3/search/jql", json={"jql": jql, "fields": base + fields, "expand": "changelog", "maxResults": 50})
            r.raise_for_status()
            return r.json()

        parents = search(f'parent in ("{epic}") ORDER BY key')
        (OUT / "search_parents.json").write_text(json.dumps(anonymise(parents, names), ensure_ascii=False, indent=1))
        keys = [i["key"] for i in parents.get("issues", [])][:20]
        if keys:
            subs = search(f"parent in ({', '.join(json.dumps(k) for k in keys)}) ORDER BY key")
            (OUT / "search_subtasks.json").write_text(json.dumps(anonymise(subs, names), ensure_ascii=False, indent=1))
        e = c.get(f"/rest/api/3/issue/{epic}", params={"fields": "summary,issuetype,project,duedate"})
        e.raise_for_status()
        (OUT / "epic.json").write_text(json.dumps(anonymise(e.json(), names), ensure_ascii=False, indent=1))
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
