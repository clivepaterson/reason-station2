-- Idempotent SQL schema setup for ReasonStation

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS "user" (
    userid SERIAL PRIMARY KEY,
    alias VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    verify_email_token UUID,
    date_created TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tracks (
    trackid SERIAL PRIMARY KEY,
    userid INTEGER NOT NULL REFERENCES "user"(userid) ON DELETE CASCADE,
    artist_alias VARCHAR(255) NOT NULL,
    track_name VARCHAR(255) NOT NULL,
    music_style VARCHAR(100) NOT NULL DEFAULT 'Other',
    filename VARCHAR(255) NOT NULL,
    track_uri VARCHAR(1024),
    date_uploaded TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS track_downloads (
    trackid INTEGER NOT NULL REFERENCES tracks(trackid) ON DELETE CASCADE,
    ip_address INET NOT NULL,
    CONSTRAINT track_downloads_pkey PRIMARY KEY (trackid, ip_address),
    date_downloaded TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS track_ratings (
    trackid INTEGER NOT NULL REFERENCES tracks(trackid) ON DELETE CASCADE,
    userid INTEGER NOT NULL REFERENCES "user"(userid) ON DELETE CASCADE,
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    date_rated TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT track_ratings_pkey PRIMARY KEY (trackid, userid)
);

CREATE INDEX IF NOT EXISTS idx_tracks_userid ON tracks(userid);
CREATE INDEX IF NOT EXISTS idx_tracks_artist_alias ON tracks(artist_alias);
CREATE INDEX IF NOT EXISTS idx_tracks_artist_alias_trgm
    ON tracks USING GIN (artist_alias gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_tracks_track_name_trgm
    ON tracks USING GIN (track_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_track_downloads_date_trackid
    ON track_downloads(date_downloaded, trackid);
CREATE INDEX IF NOT EXISTS idx_track_ratings_date_trackid
    ON track_ratings(date_rated, trackid) INCLUDE (rating);
