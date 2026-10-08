-- Idempotent SQL schema setup for ReasonStation

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

ALTER TABLE tracks DROP COLUMN IF EXISTS download_count;

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

ALTER TABLE track_downloads DROP CONSTRAINT IF EXISTS track_downloads_pkey;
ALTER TABLE track_downloads
    ADD CONSTRAINT track_downloads_pkey PRIMARY KEY (trackid, ip_address);

CREATE INDEX IF NOT EXISTS idx_tracks_userid ON tracks(userid);
CREATE INDEX IF NOT EXISTS idx_tracks_artist_alias ON tracks(artist_alias);
CREATE INDEX IF NOT EXISTS idx_track_downloads_trackid ON track_downloads(trackid);
CREATE INDEX IF NOT EXISTS idx_track_ratings_trackid ON track_ratings(trackid);
CREATE UNIQUE INDEX IF NOT EXISTS uq_track_downloads_trackid_ip_address
    ON track_downloads(trackid, ip_address);
CREATE INDEX IF NOT EXISTS idx_tracks_artist_alias_track_name_fts
    ON tracks USING GIN (
        to_tsvector('simple', artist_alias || ' ' || track_name)
    );
CREATE INDEX IF NOT EXISTS idx_user_alias ON "user"(alias);
CREATE INDEX IF NOT EXISTS idx_user_email ON "user"(email);
