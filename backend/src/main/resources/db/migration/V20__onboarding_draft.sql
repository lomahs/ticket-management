-- An anken onboarded before its epic exists: its name; null once onboarded from (or linked to) an epic.
ALTER TABLE onboarding ADD COLUMN draft_title VARCHAR;
