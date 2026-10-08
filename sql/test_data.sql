-- Development/test data for the ReasonStation PostgreSQL database.
-- Each run creates any missing seed users and tracks without duplicating them.

BEGIN;

DELETE FROM tracks
WHERE userid IN (
    SELECT userid
    FROM "user"
    WHERE email LIKE 'testuser%@example.test'
);

DELETE FROM "user"
WHERE email LIKE 'testuser%@example.test';

WITH seed_users AS (
    SELECT *
    FROM (VALUES
        (1, 'Ada Lovelace'), (2, 'Alan Turing'), (3, 'Grace Hopper'), (4, 'Katherine Johnson'),
        (5, 'Nikola Tesla'), (6, 'Marie Curie'), (7, 'Leonardo da Vinci'), (8, 'Maya Angelou'),
        (9, 'David Bowie'), (10, 'Aretha Franklin'), (11, 'Miles Davis'), (12, 'Joni Mitchell'),
        (13, 'James Baldwin'), (14, 'Virginia Woolf'), (15, 'Octavia Butler'), (16, 'Toni Morrison'),
        (17, 'Chinua Achebe'), (18, 'Gabriel Marquez'), (19, 'Frida Kahlo'), (20, 'Georgia O Keeffe'),
        (21, 'Amelia Earhart'), (22, 'Neil Armstrong'), (23, 'Carl Sagan'), (24, 'Rachel Carson'),
        (25, 'Jane Goodall'), (26, 'Steve Irwin'), (27, 'David Attenborough'), (28, 'Wangari Maathai'),
        (29, 'Miriam Makeba'), (30, 'Fela Kuti'), (31, 'Nina Simone'), (32, 'Ella Fitzgerald'),
        (33, 'Louis Armstrong'), (34, 'John Coltrane'), (35, 'Thelonious Monk'), (36, 'Billie Holiday'),
        (37, 'Patsy Cline'), (38, 'Johnny Cash'), (39, 'Dolly Parton'), (40, 'Bob Dylan'),
        (41, 'Joan Baez'), (42, 'Leonard Cohen'), (43, 'Patti Smith'), (44, 'Nick Cave'),
        (45, 'Kate Bush'), (46, 'Bjork'), (47, 'Sufjan Stevens'), (48, 'St Vincent'),
        (49, 'Thom Yorke'), (50, 'PJ Harvey'), (51, 'Haruki Murakami'), (52, 'Ursula Le Guin'),
        (53, 'Isaac Asimov'), (54, 'Philip Dick'), (55, 'Mary Shelley'), (56, 'Ray Bradbury'),
        (57, 'James Joyce'), (58, 'Zora Neale Hurston'), (59, 'Sylvia Plath'), (60, 'Ralph Ellison'),
        (61, 'Simone de Beauvoir'), (62, 'Hannah Arendt'), (63, 'bell hooks'), (64, 'James Baldwin Jr'),
        (65, 'Nelson Mandela'), (66, 'Eleanor Roosevelt'), (67, 'Desmond Tutu'), (68, 'Malala Yousafzai'),
        (69, 'Greta Thunberg'), (70, 'Tim Berners Lee'), (71, 'Linus Torvalds'), (72, 'Margaret Hamilton'),
        (73, 'Kenzaburo Oe'), (74, 'Nawal El Saadawi'), (75, 'Isabel Allende'), (76, 'Salman Rushdie'),
        (77, 'Ava DuVernay'), (78, 'Hayao Miyazaki'), (79, 'Akira Kurosawa'), (80, 'Agnes Varda'),
        (81, 'Wes Anderson'), (82, 'Sofia Coppola'), (83, 'Spike Lee'), (84, 'Guillermo del Toro'),
        (85, 'Hedy Lamarr'), (86, 'Mae Jemison'), (87, 'Sally Ride'), (88, 'Rachel McAdams'),
        (89, 'Keanu Reeves'), (90, 'Viola Davis'), (91, 'Chadwick Boseman'), (92, 'Tilda Swinton'),
        (93, 'Robin Williams'), (94, 'Audrey Hepburn'), (95, 'Sidney Poitier'), (96, 'Paul Newman'),
        (97, 'Marlon Brando'), (98, 'Gillian Anderson'), (99, 'Pedro Almodovar'), (100, 'Taika Waititi')
    ) AS names(number, alias)
)
INSERT INTO "user" (
    alias,
    email,
    password_hash,
    email_verified,
    verify_email_token,
    date_created
)
SELECT
    alias,
    'testuser' || LPAD(number::text, 3, '0') || '@example.test',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    TRUE,
    NULL,
    CURRENT_TIMESTAMP - random() * INTERVAL '10 years'
FROM seed_users
ON CONFLICT DO NOTHING;

UPDATE "user"
SET date_created = CURRENT_TIMESTAMP - random() * INTERVAL '10 years'
WHERE email LIKE 'testuser%@example.test';

WITH seed_users AS (
    SELECT *
    FROM (VALUES
        (1, 'Ada Lovelace'), (2, 'Alan Turing'), (3, 'Grace Hopper'), (4, 'Katherine Johnson'),
        (5, 'Nikola Tesla'), (6, 'Marie Curie'), (7, 'Leonardo da Vinci'), (8, 'Maya Angelou'),
        (9, 'David Bowie'), (10, 'Aretha Franklin'), (11, 'Miles Davis'), (12, 'Joni Mitchell'),
        (13, 'James Baldwin'), (14, 'Virginia Woolf'), (15, 'Octavia Butler'), (16, 'Toni Morrison'),
        (17, 'Chinua Achebe'), (18, 'Gabriel Marquez'), (19, 'Frida Kahlo'), (20, 'Georgia O Keeffe'),
        (21, 'Amelia Earhart'), (22, 'Neil Armstrong'), (23, 'Carl Sagan'), (24, 'Rachel Carson'),
        (25, 'Jane Goodall'), (26, 'Steve Irwin'), (27, 'David Attenborough'), (28, 'Wangari Maathai'),
        (29, 'Miriam Makeba'), (30, 'Fela Kuti'), (31, 'Nina Simone'), (32, 'Ella Fitzgerald'),
        (33, 'Louis Armstrong'), (34, 'John Coltrane'), (35, 'Thelonious Monk'), (36, 'Billie Holiday'),
        (37, 'Patsy Cline'), (38, 'Johnny Cash'), (39, 'Dolly Parton'), (40, 'Bob Dylan'),
        (41, 'Joan Baez'), (42, 'Leonard Cohen'), (43, 'Patti Smith'), (44, 'Nick Cave'),
        (45, 'Kate Bush'), (46, 'Bjork'), (47, 'Sufjan Stevens'), (48, 'St Vincent'),
        (49, 'Thom Yorke'), (50, 'PJ Harvey'), (51, 'Haruki Murakami'), (52, 'Ursula Le Guin'),
        (53, 'Isaac Asimov'), (54, 'Philip Dick'), (55, 'Mary Shelley'), (56, 'Ray Bradbury'),
        (57, 'James Joyce'), (58, 'Zora Neale Hurston'), (59, 'Sylvia Plath'), (60, 'Ralph Ellison'),
        (61, 'Simone de Beauvoir'), (62, 'Hannah Arendt'), (63, 'bell hooks'), (64, 'James Baldwin Jr'),
        (65, 'Nelson Mandela'), (66, 'Eleanor Roosevelt'), (67, 'Desmond Tutu'), (68, 'Malala Yousafzai'),
        (69, 'Greta Thunberg'), (70, 'Tim Berners Lee'), (71, 'Linus Torvalds'), (72, 'Margaret Hamilton'),
        (73, 'Kenzaburo Oe'), (74, 'Nawal El Saadawi'), (75, 'Isabel Allende'), (76, 'Salman Rushdie'),
        (77, 'Ava DuVernay'), (78, 'Hayao Miyazaki'), (79, 'Akira Kurosawa'), (80, 'Agnes Varda'),
        (81, 'Wes Anderson'), (82, 'Sofia Coppola'), (83, 'Spike Lee'), (84, 'Guillermo del Toro'),
        (85, 'Hedy Lamarr'), (86, 'Mae Jemison'), (87, 'Sally Ride'), (88, 'Rachel McAdams'),
        (89, 'Keanu Reeves'), (90, 'Viola Davis'), (91, 'Chadwick Boseman'), (92, 'Tilda Swinton'),
        (93, 'Robin Williams'), (94, 'Audrey Hepburn'), (95, 'Sidney Poitier'), (96, 'Paul Newman'),
        (97, 'Marlon Brando'), (98, 'Gillian Anderson'), (99, 'Pedro Almodovar'), (100, 'Taika Waititi')
    ) AS names(number, alias)
),
seed_tracks AS (
    SELECT
        users.number,
        users.alias,
        tracks.track_number
    FROM seed_users AS users
    CROSS JOIN LATERAL generate_series(1, 5 + ((users.number - 1) % 6)) AS tracks(track_number)
)
INSERT INTO tracks (
    userid,
    artist_alias,
    track_name,
    music_style,
    filename,
    track_uri,
    date_uploaded
)
SELECT
    database_users.userid,
    seed_tracks.alias,
    CASE seed_tracks.track_number
        WHEN 1 THEN 'Midnight on the Meridian'
        WHEN 2 THEN 'Lanterns in the Rain'
        WHEN 3 THEN 'Gravity Has a Memory'
        WHEN 4 THEN 'Postcards from Tomorrow'
        WHEN 5 THEN 'The Mapmaker''s Dream'
        WHEN 6 THEN 'Signals Across the Static'
        WHEN 7 THEN 'A Quiet Kind of Thunder'
        WHEN 8 THEN 'Paper Moons and Satellites'
        WHEN 9 THEN 'The Last Train to Elsewhere'
        ELSE 'After the Neon Fades'
    END,
    CASE ((seed_tracks.number + seed_tracks.track_number - 2) % 5)
        WHEN 0 THEN 'Ambient'
        WHEN 1 THEN 'Electronic'
        WHEN 2 THEN 'Hip-Hop'
        WHEN 3 THEN 'Indie'
        ELSE 'Rock'
    END,
    'test-track-' || seed_tracks.number || '-' || seed_tracks.track_number || '.mp3',
    's3://tracks/tracks/deb9bdd7-36d8-4d19-840c-896f555118b6',
    CURRENT_TIMESTAMP - random() * INTERVAL '10 years'
FROM seed_tracks
JOIN "user" AS database_users
    ON database_users.alias = seed_tracks.alias
WHERE NOT EXISTS (
    SELECT 1
    FROM tracks AS existing_tracks
    WHERE existing_tracks.userid = database_users.userid
      AND existing_tracks.filename = 'test-track-' || seed_tracks.number || '-' || seed_tracks.track_number || '.mp3'
);

UPDATE tracks
SET
    date_uploaded = CURRENT_TIMESTAMP - random() * INTERVAL '10 years'
WHERE filename LIKE 'test-track-%';

DELETE FROM track_ratings
WHERE trackid IN (
    SELECT trackid
    FROM tracks
    WHERE filename LIKE 'test-track-%'
);

WITH test_users AS (
    SELECT
        userid,
        ROW_NUMBER() OVER (ORDER BY userid) AS user_rank
    FROM "user"
    WHERE email LIKE 'testuser%@example.test'
),
ranked_ratings AS (
    SELECT
        tracks.trackid,
        test_users.userid,
        ROW_NUMBER() OVER (
            PARTITION BY tracks.trackid
            ORDER BY test_users.user_rank
        ) AS rating_rank
    FROM tracks
    CROSS JOIN test_users
    WHERE tracks.filename LIKE 'test-track-%'
      AND test_users.userid <> tracks.userid
)
INSERT INTO track_ratings (
    trackid,
    userid,
    rating
)
SELECT
    trackid,
    userid,
    (1 + ((trackid + rating_rank) % 5))::smallint
FROM ranked_ratings
WHERE rating_rank <= 3 + ((trackid - 1) % 5);

COMMIT;
