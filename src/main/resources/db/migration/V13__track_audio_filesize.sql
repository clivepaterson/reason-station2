ALTER TABLE tracks ADD COLUMN audio_filesize BIGINT CHECK (audio_filesize >= 0);
