ALTER TABLE "user" ADD COLUMN admin BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE "user"
SET admin = TRUE
WHERE email = 'clive.paterson@gmail.com';
