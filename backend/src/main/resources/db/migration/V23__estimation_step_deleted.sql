-- A step deleted from the Detail plan for good: unlike a removed one it cannot be restored, and clearing the plan keeps it out.
ALTER TABLE estimation_steps ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;
