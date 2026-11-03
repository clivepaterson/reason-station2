CREATE INDEX idx_patches_filter_trgm
    ON patches USING GIN (
        (patch_name || ' ' || COALESCE(description, '') || ' ' || filename) gin_trgm_ops
    );
