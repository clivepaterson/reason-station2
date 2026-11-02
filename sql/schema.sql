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
    filesize BIGINT,
    track_uri VARCHAR(1024),
    date_uploaded TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE tracks ADD COLUMN IF NOT EXISTS filesize BIGINT;

CREATE TABLE IF NOT EXISTS patches (
    patchid SERIAL PRIMARY KEY,
    userid INTEGER NOT NULL REFERENCES "user"(userid) ON DELETE CASCADE,
    artist_alias VARCHAR(255) NOT NULL,
    patch_name VARCHAR(255) NOT NULL,
    description VARCHAR(200),
    filename VARCHAR(255) NOT NULL,
    ext VARCHAR(16),
    filesize BIGINT,
    patch_uri VARCHAR(1024),
    date_uploaded TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE patches ADD COLUMN IF NOT EXISTS ext VARCHAR(16);
ALTER TABLE patches ADD COLUMN IF NOT EXISTS description VARCHAR(200);
UPDATE patches
SET ext = LOWER(SUBSTRING(filename FROM '\.[^.]+$'))
WHERE ext IS NULL;

CREATE TABLE IF NOT EXISTS track_downloads (
    trackid INTEGER NOT NULL REFERENCES tracks(trackid) ON DELETE CASCADE,
    ip_address INET NOT NULL,
    CONSTRAINT track_downloads_pkey PRIMARY KEY (trackid, ip_address),
    date_downloaded TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS track_ratings (
    trackid INTEGER NOT NULL REFERENCES tracks(trackid) ON DELETE CASCADE,
    ip_address INET NOT NULL,
    rating SMALLINT NOT NULL,
    date_rated TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT track_ratings_trackid_ip_address_key UNIQUE (trackid, ip_address)
);

ALTER TABLE track_ratings
    DROP CONSTRAINT IF EXISTS track_ratings_rating_check;
ALTER TABLE track_ratings
    ADD CONSTRAINT track_ratings_rating_check CHECK (rating BETWEEN 1 AND 10);

CREATE INDEX IF NOT EXISTS idx_tracks_userid ON tracks(userid);
CREATE INDEX IF NOT EXISTS idx_tracks_userid_artist_alias
    ON tracks(userid, artist_alias);
CREATE INDEX IF NOT EXISTS idx_patches_userid ON patches(userid);
CREATE INDEX IF NOT EXISTS idx_patches_userid_artist_alias
    ON patches(userid, artist_alias);
CREATE INDEX IF NOT EXISTS idx_patches_ext ON patches(ext);
CREATE INDEX IF NOT EXISTS idx_tracks_artist_alias ON tracks(artist_alias);
CREATE INDEX IF NOT EXISTS idx_tracks_artist_alias_date_uploaded
    ON tracks(artist_alias, date_uploaded DESC, trackid);
CREATE INDEX IF NOT EXISTS idx_tracks_userid_filename
    ON tracks(userid, filename);
CREATE INDEX IF NOT EXISTS idx_user_date_created_userid
    ON "user"(date_created DESC, userid);
CREATE INDEX IF NOT EXISTS idx_tracks_date_uploaded_trackid
    ON tracks(date_uploaded DESC, trackid);
CREATE INDEX IF NOT EXISTS idx_tracks_track_name_trackid
    ON tracks(track_name, trackid);
CREATE INDEX IF NOT EXISTS idx_tracks_music_style_trackid
    ON tracks(music_style, trackid);
CREATE INDEX IF NOT EXISTS idx_tracks_filename_trackid
    ON tracks(filename, trackid);
CREATE INDEX IF NOT EXISTS idx_patches_date_uploaded_patchid
    ON patches(date_uploaded DESC, patchid);
CREATE INDEX IF NOT EXISTS idx_patches_artist_alias_patchid
    ON patches(artist_alias, patchid);
CREATE INDEX IF NOT EXISTS idx_patches_patch_name_patchid
    ON patches(patch_name, patchid);
CREATE INDEX IF NOT EXISTS idx_patches_filename_patchid
    ON patches(filename, patchid);
CREATE INDEX IF NOT EXISTS idx_patches_ext_patchid
    ON patches(ext, patchid);
CREATE INDEX IF NOT EXISTS idx_tracks_artist_alias_trgm
    ON tracks USING GIN (artist_alias gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_tracks_track_name_trgm
    ON tracks USING GIN (track_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_track_downloads_date_trackid
    ON track_downloads(date_downloaded, trackid);
CREATE INDEX IF NOT EXISTS idx_track_ratings_date_trackid
    ON track_ratings(date_rated, trackid) INCLUDE (rating);
