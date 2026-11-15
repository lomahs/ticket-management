-- The Original Estimate each capture saw, beside the remaining: a change approved after the capture (even later that day) is
-- added back to the captured remaining (docs/rules.md). Null on captures taken before.
ALTER TABLE issue_remaining_days ADD COLUMN estimate_h REAL;
