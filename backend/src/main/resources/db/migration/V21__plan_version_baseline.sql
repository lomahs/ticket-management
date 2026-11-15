-- 1 when a saved version holds each sub-task's Original Estimate: the first such version of an anken is its Baseline plan.
ALTER TABLE plan_versions ADD COLUMN with_estimates INTEGER NOT NULL DEFAULT 0;
