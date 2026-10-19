-- First reporter of an issue (the BAL of a function, from its Create sub-task); filled by the next full sync.
ALTER TABLE issues ADD COLUMN reporter_id VARCHAR;
