CREATE INDEX idx_tracks_filter_trgm
    ON tracks USING GIN (
        (artist_alias || ' ' || track_name || ' ' || filename) gin_trgm_ops
    );
