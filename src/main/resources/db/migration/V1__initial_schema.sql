CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE "user" (
    userid SERIAL PRIMARY KEY,
    alias VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    verify_email_token UUID,
    date_created TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tracks (
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

CREATE TABLE patches (
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

CREATE TABLE track_downloads (
    trackid INTEGER NOT NULL REFERENCES tracks(trackid) ON DELETE CASCADE,
    ip_address INET NOT NULL,
    CONSTRAINT track_downloads_pkey PRIMARY KEY (trackid, ip_address),
    date_downloaded TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE track_ratings (
    trackid INTEGER NOT NULL REFERENCES tracks(trackid) ON DELETE CASCADE,
    ip_address INET NOT NULL,
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 10),
    date_rated TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT track_ratings_trackid_ip_address_key UNIQUE (trackid, ip_address)
);

CREATE INDEX idx_tracks_userid ON tracks(userid);
CREATE INDEX idx_tracks_userid_artist_alias ON tracks(userid, artist_alias);
CREATE INDEX idx_patches_userid ON patches(userid);
CREATE INDEX idx_patches_userid_artist_alias ON patches(userid, artist_alias);
CREATE INDEX idx_patches_ext ON patches(ext);
CREATE INDEX idx_tracks_artist_alias ON tracks(artist_alias);
CREATE INDEX idx_tracks_artist_alias_date_uploaded
    ON tracks(artist_alias, date_uploaded DESC, trackid);
CREATE INDEX idx_tracks_userid_filename ON tracks(userid, filename);
CREATE INDEX idx_user_date_created_userid
    ON "user"(date_created DESC, userid);
CREATE INDEX idx_tracks_date_uploaded_trackid
    ON tracks(date_uploaded DESC, trackid);
CREATE INDEX idx_tracks_track_name_trackid ON tracks(track_name, trackid);
CREATE INDEX idx_tracks_music_style_trackid ON tracks(music_style, trackid);
CREATE INDEX idx_tracks_filename_trackid ON tracks(filename, trackid);
CREATE INDEX idx_patches_date_uploaded_patchid
    ON patches(date_uploaded DESC, patchid);
CREATE INDEX idx_patches_artist_alias_patchid ON patches(artist_alias, patchid);
CREATE INDEX idx_patches_patch_name_patchid ON patches(patch_name, patchid);
CREATE INDEX idx_patches_filename_patchid ON patches(filename, patchid);
CREATE INDEX idx_patches_ext_patchid ON patches(ext, patchid);
CREATE INDEX idx_tracks_artist_alias_trgm
    ON tracks USING GIN (artist_alias gin_trgm_ops);
CREATE INDEX idx_tracks_track_name_trgm
    ON tracks USING GIN (track_name gin_trgm_ops);
CREATE INDEX idx_track_downloads_date_trackid
    ON track_downloads(date_downloaded, trackid);
CREATE INDEX idx_track_ratings_date_trackid
    ON track_ratings(date_rated, trackid) INCLUDE (rating);
