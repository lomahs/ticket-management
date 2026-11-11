-- Hours are kept in hundredths (Numbers.hours): the values saved before are rounded the same way.
UPDATE issues SET estimate_h = ROUND(estimate_h, 2), remaining_h = ROUND(remaining_h, 2);
UPDATE worklogs SET hours = ROUND(hours, 2);
UPDATE allocations SET hours_per_day = ROUND(hours_per_day, 2);
UPDATE estimation_rows SET effort_h = ROUND(effort_h, 2);
UPDATE estimation_steps SET effort_h = ROUND(effort_h, 2) WHERE effort_h IS NOT NULL;
UPDATE master_schedule_phases SET total_effort_h = ROUND(total_effort_h, 2);
UPDATE estimation_plan_placed SET hours = ROUND(hours, 2);
UPDATE estimation_plan_parts SET hours = ROUND(hours, 2);
