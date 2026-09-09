-- Run once on the local public_service experiment database before starting the
-- @Version application. This is the only schema change for this experiment.
USE public_service;
ALTER TABLE program ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
