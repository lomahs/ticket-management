-- The name rules now have a Task pattern and a Sub-task pattern with new placeholders: every saved pattern (the default and
-- each anken's) goes back to the new defaults. The step aliases are kept.
UPDATE settings SET value = json_set(value,
    '$.name_rules.rule', '[{anken_prefix}][{os_task}][{sub_phase_prefix}]_{function}_{step?}',
    '$.name_rules.task_rule', '[{anken_prefix}][{os_task}] {sub_phase}');

UPDATE settings SET value = json_set(value, '$.anken', (
    SELECT json_group_object(a.key, json_set(a.value,
        '$.name_rules.rule', '[{anken_prefix}][{os_task}][{sub_phase_prefix}]_{function}_{step?}',
        '$.name_rules.task_rule', '[{anken_prefix}][{os_task}] {sub_phase}'))
    FROM json_each(settings.value, '$.anken') AS a))
WHERE json_type(value, '$.anken') = 'object';
