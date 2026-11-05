-- The labels entered at Setting Phase, separated by spaces (a Jira label has none): the sub-tasks pushed from the detail plan get them too.
ALTER TABLE onboarding ADD COLUMN labels TEXT NOT NULL DEFAULT '';
