# Archive catalog import

`archive_data.sql` creates and populates `archive_songs` and `archive_refills`
as a standalone PostgreSQL script.
The table definitions, `date_created` columns, search indexes and date-ordering
indexes all live in Flyway migration `V10__archive_catalogs.sql`, along with
the same 726 archive INSERT statements. Flyway therefore creates and populates
the catalogs automatically during deployment. No file uploads are performed
by either SQL file.

The standalone import remains available for manual imports or data refreshes:

```powershell
psql -h localhost -U postgres -d mydb -v ON_ERROR_STOP=1 -f .\sql\archive_data.sql
```

The import runs in a transaction and uses stable keys to update existing rows
on reruns. It does not delete old file records. Generated metadata-only records
from earlier imports and specific duplicate-file records excluded by the
collector are removed.

## Collected files

The ignored `archive-files` directory contains every matching local file,
including files without names, creators or other HTML metadata. Original paths
under the scrape root are preserved beneath `songs` or `refills`, so duplicate
filenames and multiple versions are not overwritten or discarded. Files are
copied, never moved. `inventory.json` records source/destination paths, match
methods, byte sizes and SHA-256 checksums.

The SQL includes one row per original local filename within each catalog
(case-insensitive). When original filenames repeat, the row with the most descriptive metadata is retained;
ties are resolved by source path for deterministic reruns. All physical copies
remain in the collection. The inventory's `included_in_sql` flag identifies
which copies are represented in the import.

After deduplication, the SQL `filename` values have the leading prefix matching
`^(song|refill)-[a-zA-Z0-9]+-[a-zA-Z0-9]+-` removed. For example,
`song-14-609438a978e8873b-fusion.rps` becomes `fusion.rps`. Entries whose cleaned
filenames coincide are retained, including different file versions. Only the
catalog filename is cleaned: physical files, `archive_key`, `source_path` and
`s3_uri` retain their original paths.

Metadata is read from all HTML files beneath the scrape's `songs` and `refills`
directories. Songs use both browse rows and detail pages; detail values take
precedence where present. File matching uses numeric archive IDs first, then
local HTML download links, then an unambiguous original download filename.
Hexadecimal download hashes are not treated as archive IDs. No artist or title
is guessed from an unmatched filename.

HTML entries without local files are excluded. Every imported row has a
collected local file and a validated, non-NULL S3 destination. Missing
descriptive metadata is still SQL `NULL`. `date_created` is the archive
publication date, `avr` is the archived song
average rating, `filesize` is the actual local file size in bytes, and
`extension` is lowercase without a leading dot.
`archive_song_id` / `archive_refill_id` preserve original IDs where known;
`archive_key` identifies each file independently.
`source_path` is relative to the scrape root; `metadata_source` identifies
the HTML page used to enrich the row.

Only `.rps`, `.rns`, `.zyp`, `.cmb`, `.xwv`, `.rx7`, `.sm4`, `.rx2` and `.rex`
are collected. Other extensions, including `.bin` and `.rfl`, are not included.

## Search and date ordering

Both tables have PostgreSQL `pg_trgm` GIN indexes on `name`, `filename` and
`creator` for case-insensitive substring searches using `ILIKE`. Date-ordering
indexes support newest-first results with unknown dates last; `archive_key`
provides a stable tie-breaker. For example:

```sql
SELECT name, creator, filename, date_created, s3_uri
FROM archive_songs
WHERE name ILIKE '%fusion%'
   OR filename ILIKE '%fusion%'
   OR creator ILIKE '%fusion%'
ORDER BY date_created DESC NULLS LAST, archive_key;

SELECT name, creator, filename, date_created, s3_uri
FROM archive_refills
WHERE name ILIKE '%piano%'
   OR filename ILIKE '%piano%'
   OR creator ILIKE '%piano%'
ORDER BY date_created DESC NULLS LAST, archive_key;
```

When using these queries in application code, bind the search pattern as a
parameter rather than concatenating user input into SQL. Entries with NULL
names can still be found by filename. Very short search terms may require a
table scan. The standalone import needs permission to install `pg_trgm` if it
is not already available.

## Regenerate

From the project root, using Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Collect-Archive.ps1
```

Defaults are `C:\reasonstation_scape`, `s3://reasonarchive`,
`archive-files`, and `sql\archive_data.sql`. Override them with `-SourceRoot`,
`-S3Prefix`, `-OutputDirectory` and `-SqlPath`. Existing collected files are
checked by checksum, and identical copies are left alone. Files no longer
present in the source are not automatically removed from the collection.

The collector excludes V10's embedded data when reading its schema, so the
standalone import contains each INSERT only once. Before the first deployment,
refresh both SQL files together with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Collect-Archive.ps1 -UpdateMigration
```

Normal regeneration does not modify V10. Once V10 has been applied to a
database, do not use `-UpdateMigration`; use a new migration for any subsequent
deployment changes to avoid Flyway checksum mismatches.

## Upload manually

The SQL contains intended S3 destinations; it does not upload files or confirm
that objects exist. The same `s3://reasonarchive/...` values work for both
environments: the bucket/key identifies the object, while the CLI or application
endpoint determines which storage service is used.

| Environment | S3 API endpoint | Region | Bucket |
| --- | --- | --- | --- |
| Local SeaweedFS | `http://localhost:8333` | `us-east-1` | `reasonarchive` |
| Production AWS S3 | `https://s3.ap-southeast-1.amazonaws.com` | `ap-southeast-1` (Singapore) | `reasonarchive` |

`https://ap-southeast-1.console.aws.amazon.com/s3/buckets/` is the AWS browser
console, not an S3 API endpoint. Do not use it as `--endpoint-url` or
`S3_ENDPOINT`.

### Local SeaweedFS

Start the existing Docker Compose service:

```powershell
docker compose up -d seaweedfs
```

Create a separate AWS CLI profile using the local access key and secret key
from the ignored `s3.json` mounted by Docker Compose. Do not use AWS production
credentials for this profile. Set its default region to `us-east-1` when prompted:

```powershell
aws configure --profile seaweedfs
aws configure set s3.addressing_style path --profile seaweedfs
```

Create the local bucket once if it does not already exist:

```powershell
aws --profile seaweedfs --region us-east-1 --endpoint-url http://localhost:8333 s3 mb s3://reasonarchive
```

Preview both uploads, then remove `--dryrun` to copy the files:

```powershell
aws --profile seaweedfs --region us-east-1 --endpoint-url http://localhost:8333 s3 sync .\archive-files\songs s3://reasonarchive/songs --dryrun
aws --profile seaweedfs --region us-east-1 --endpoint-url http://localhost:8333 s3 sync .\archive-files\refills s3://reasonarchive/refills --dryrun
```

The endpoint and credentials are local only. HTTP is appropriate only for
trusted local testing; use HTTPS outside that environment. Production S3
encryption options are intentionally not passed to SeaweedFS.

### Production AWS S3

These examples assume an AWS CLI profile named `production` is already
configured for the AWS account that owns `reasonarchive`, preferably using
SSO or other temporary credentials. The bucket must exist in `ap-southeast-1`.
Explicit endpoints keep the commands independent of any local endpoint
configured in the shell or profile.

Preview both uploads, then remove `--dryrun` to copy the files:

```powershell
aws --profile production --region ap-southeast-1 --endpoint-url https://s3.ap-southeast-1.amazonaws.com s3 sync .\archive-files\songs s3://reasonarchive/songs --sse AES256 --dryrun
aws --profile production --region ap-southeast-1 --endpoint-url https://s3.ap-southeast-1.amazonaws.com s3 sync .\archive-files\refills s3://reasonarchive/refills --sse AES256 --dryrun
```

These commands exclude the inventory by uploading only the two content
directories. They do not delete remote objects. Keep the bucket private with
Block Public Access enabled, use temporary credentials with least-privilege
access to these prefixes, and retain TLS verification. Consider S3 access
logging, CloudTrail data events and CloudWatch metrics for auditing; encrypt
log destinations as well. Upload requests, storage, downloads and optional
logging/monitoring incur AWS charges. See the
[AWS CLI sync reference](https://docs.aws.amazon.com/cli/latest/reference/s3/sync.html),
[S3 security guidance](https://docs.aws.amazon.com/AmazonS3/latest/userguide/security-best-practices.html)
and [S3 pricing](https://aws.amazon.com/s3/pricing/).

### Application endpoint settings

The application already supports both storage services through environment
variables. Use these values when configuring a client to access the archive:

| Variable | Local SeaweedFS | Production AWS S3 |
| --- | --- | --- |
| `S3_ENDPOINT` | `http://localhost:8333` | `https://s3.ap-southeast-1.amazonaws.com` |
| `S3_PUBLIC_ENDPOINT` | `http://localhost:8333` | `https://s3.ap-southeast-1.amazonaws.com` |
| `S3_REGION` | `us-east-1` | `ap-southeast-1` |
| `S3_BUCKET` | `reasonarchive` | `reasonarchive` |

Local credentials must match `s3.json`. In production, leave `S3_ACCESS_KEY`
and `S3_SECRET_KEY` unset when using the application's IAM role. An application
inside the Compose network would use `http://seaweedfs:8333` internally while
keeping a browser-reachable public endpoint for signed download URLs.

The existing application's default bucket is `tracks`; changing `S3_BUCKET`
also changes the target for its existing upload/download functionality. These
archive tables are separate catalogs, and uploading/importing them does not
automatically add archive browsing to the application.

### Archive downloads

The archive pages download files through `/api/catalog/archived-songs/download`
and `/api/catalog/archived-refills/download`, using the row's `archive_key` as
the `key` query parameter. The application looks up the stored `s3_uri` and
redirects to a short-lived signed attachment URL; clients cannot supply a
storage location. Rows without a stored destination show an unavailable button.
An intended destination is not proof that an object has been uploaded.

Archive downloads use the bucket in each row's `s3_uri`, not `S3_BUCKET`, so
there is no need to change the existing upload bucket. The ECS template grants
read-only `s3:GetObject` access over HTTPS to the archive bucket's `songs/` and
`refills/` prefixes. Its `ArchiveStorageBucketName` parameter defaults to
`reasonarchive`; pass `-ArchiveBucketName` to `deploy-blue-green.ps1` for another
bucket, keeping it consistent with the imported archive destinations. Apply
the updated ECS stack before using archive downloads in production.
