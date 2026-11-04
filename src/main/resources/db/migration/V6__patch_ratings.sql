CREATE TABLE patch_ratings (
    patchid INTEGER NOT NULL REFERENCES patches(patchid) ON DELETE CASCADE,
    ip_address INET NOT NULL,
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 10),
    date_rated TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT patch_ratings_pkey PRIMARY KEY (patchid, ip_address)
);
