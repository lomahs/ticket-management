"""Read-only checks against a real Jira site. Skipped unless run with `-m jira_live` and TRACKER_JIRA_* set."""

import os

import pytest

from tracker.adapters.jira.client import JiraCloudReader, JiraConfig

pytestmark = pytest.mark.jira_live
ENV = ("TRACKER_JIRA_URL", "TRACKER_JIRA_EMAIL", "TRACKER_JIRA_TOKEN", "TRACKER_JIRA_EPIC")


@pytest.fixture()
def reader():
    if not all(os.environ.get(k) for k in ENV):
        pytest.skip("set " + ", ".join(ENV))
    fields = tuple(os.environ.get("TRACKER_JIRA_FIELDS", "customfield_10020,customfield_10016,timeoriginalestimate").split(","))
    cfg = JiraConfig(os.environ["TRACKER_JIRA_URL"], os.environ["TRACKER_JIRA_EMAIL"], os.environ["TRACKER_JIRA_TOKEN"], fields)
    return JiraCloudReader(lambda: cfg)


def test_connection(reader):
    assert reader.test_connection()


def test_epic_and_its_issues_can_be_read(reader):
    epic = os.environ["TRACKER_JIRA_EPIC"]
    assert reader.get_epic(epic) is not None
    snaps = list(reader.search([epic], None))
    assert snaps, "the epic has no child issues"
    assert all(s.status_name for s in snaps)
