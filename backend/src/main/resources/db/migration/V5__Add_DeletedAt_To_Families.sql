-- ========================================================================
-- V5: Add deleted_at column to families table (soft delete for deleteFamily API)
-- ========================================================================

ALTER TABLE families ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_families_deleted_at ON families (deleted_at) WHERE deleted_at IS NULL;
