-- The Jira labels of an issue, separated by spaces (a Jira label has none). Filled by the next full sync.
ALTER TABLE issues ADD COLUMN labels TEXT NOT NULL DEFAULT '';
