ALTER TABLE trips ADD COLUMN hitcount integer;
UPDATE trips SET hitcount=0;
