[CmdletBinding()]
param(
    [string]$SourceRoot = 'C:\reasonstation_scape',
    [string]$S3Prefix = 's3://reasonarchive',
    [string]$OutputDirectory,
    [string]$SqlPath,
    [switch]$UpdateMigration
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot '..\archive-files' }
if (-not $SqlPath) { $SqlPath = Join-Path $PSScriptRoot '..\sql\archive_data.sql' }
$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path.TrimEnd('\')
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$SqlPath = [IO.Path]::GetFullPath($SqlPath)
$S3Prefix = $S3Prefix.TrimEnd('/')
if ($S3Prefix -notmatch '^s3://[a-z0-9][a-z0-9.-]+(?:/[^\\]*)?$') {
    throw 'S3Prefix must be an s3://bucket URI with an optional prefix.'
}
if ($OutputDirectory.StartsWith($SourceRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    $OutputDirectory -eq $SourceRoot) {
    throw 'OutputDirectory must not be inside SourceRoot.'
}

function Get-MatchValue([string]$Html, [string]$Pattern) {
    $match = [regex]::Match($Html, $Pattern, 'IgnoreCase, Singleline')
    if ($match.Success) { $match.Groups[1].Value }
}

function Get-Text([string]$Html) {
    $clean = [regex]::Replace($Html, '<span\b[^>]*class="broken-[^"]*-label"[^>]*>.*?</span>', '', 'IgnoreCase, Singleline')
    $clean = [regex]::Replace($clean, '<[^>]+>', ' ')
    $clean = [Net.WebUtility]::HtmlDecode($clean)
    $clean = [regex]::Replace($clean, '\s+', ' ').Trim()
    if ($clean.Length -gt 0) { $clean }
}

function Get-ArchiveDate([string]$Text) {
    $match = [regex]::Match($Text, '\b(\d{2}-\d{2}-(?:\d{4}|\d{2}))\b')
    if ($match.Success) {
        $date = [datetime]::MinValue
        $formats = [string[]]@('dd-MM-yyyy', 'dd-MM-yy')
        $culture = [Globalization.CultureInfo]::InvariantCulture.Clone()
        # The site operated in the 2000s; fix the two-digit year cutoff across hosts.
        $culture.DateTimeFormat.Calendar.TwoDigitYearMax = 2099
        if (-not [datetime]::TryParseExact($match.Groups[1].Value, $formats, $culture,
                [Globalization.DateTimeStyles]::None, [ref]$date)) {
            throw "Invalid archive date: $($match.Groups[1].Value)"
        }
        $date.ToString('yyyy-MM-dd')
    }
}

function Get-Links([string]$Html) {
    foreach ($match in [regex]::Matches($Html, '<a\b([^>]*)>(.*?)</a>', 'IgnoreCase, Singleline')) {
        $attributes = @{}
        foreach ($attribute in [regex]::Matches($match.Groups[1].Value, '([\w-]+)\s*=\s*["''](.*?)["'']', 'Singleline')) {
            $attributes[$attribute.Groups[1].Value.ToLowerInvariant()] = [Net.WebUtility]::HtmlDecode($attribute.Groups[2].Value)
        }
        [pscustomobject]@{ Attributes = $attributes; Text = (Get-Text $match.Groups[2].Value) }
    }
}

function Get-Id([string]$Url, [string]$Kind) {
    $pattern = if ($Kind -eq 'song') { '(?:[?&]song=|song-)(\d{1,15})(?:[&#./-]|$)' } else { '(?:[?&]id=|refill-)(\d{1,15})(?:[&#./-]|$)' }
    $value = Get-MatchValue $Url $pattern
    if ($value) { [long]$value }
}

function Get-DownloadFilename([string]$Url) {
    $value = Get-MatchValue $Url '/download\.php/([^?/#]+)'
    if ($value) { [Uri]::UnescapeDataString($value) }
}

function New-Metadata([long]$Id, [string]$Source) {
    @{
        Id = $Id; name = $null; creator = $null; style = $null; date_created = $null
        avr = $null; filename = $null; metadata_source = $(if ($Source) { $Source } else { $null })
    }
}

function Merge-Metadata($Catalog, $Item) {
    $key = [string]$Item.Id
    if (-not $Catalog.ContainsKey($key)) { $Catalog[$key] = $Item; return }
    foreach ($field in @('name', 'creator', 'style', 'date_created', 'avr', 'filename', 'metadata_source')) {
        if ($null -ne $Item[$field] -and $Item[$field] -ne '') { $Catalog[$key][$field] = $Item[$field] }
    }
}

$catalogs = @{ song = @{}; refill = @{} }
$linkedFiles = @{ song = @{}; refill = @{} }
$htmlCounts = @{}
foreach ($kind in @('song', 'refill')) {
    $htmlFiles = @(Get-ChildItem -LiteralPath (Join-Path $SourceRoot ($kind + 's')) -Filter '*.html' -Recurse -File |
        Sort-Object @{ Expression = { $_.Name -match '^song-\d+\.html$' } }, FullName)
    $htmlCounts[$kind] = $htmlFiles.Count
    foreach ($file in $htmlFiles) {
        $html = [IO.File]::ReadAllText($file.FullName)
        $relative = $file.FullName.Substring($SourceRoot.Length + 1)
        foreach ($row in [regex]::Matches($html, '<tr\b[^>]*>(.*?)</tr>', 'IgnoreCase, Singleline')) {
            $cells = @([regex]::Matches($row.Groups[1].Value, '<td\b[^>]*>(.*?)</td>', 'IgnoreCase, Singleline'))
            if ($cells.Count -lt 4) { continue }
            $links = @(Get-Links $cells[1].Groups[1].Value)
            $item = $null
            foreach ($link in $links) {
                $attribute = if ($kind -eq 'song') { 'data-song-url' } else { 'data-download-url' }
                $url = $link.Attributes[$attribute]
                if (-not $url) { $url = $link.Attributes['href'] }
                $id = Get-Id $url $kind
                if ($null -eq $id) { continue }
                $item = New-Metadata $id $relative
                $item.name = $link.Text
                $item.date_created = Get-ArchiveDate (Get-Text $cells[1].Groups[1].Value)
                $item.filename = Get-DownloadFilename $url
                if ($kind -eq 'song') {
                    $creatorLinks = @($links | Where-Object {
                        $_.Attributes['href'] -match '(?:profile|user-)' -or $_.Attributes.ContainsKey('data-profile-url')
                    })
                    if ($creatorLinks.Count -gt 0) { $item.creator = $creatorLinks[0].Text }
                    $item.style = Get-Text $cells[2].Groups[1].Value
                    $item.avr = Get-MatchValue (Get-Text $cells[3].Groups[1].Value) '\bavr:\s*(\d+(?:\.\d+)?)'
                } else {
                    $item.creator = Get-Text $cells[2].Groups[1].Value
                }
                Merge-Metadata $catalogs[$kind] $item
                break
            }
        }
        if ($kind -eq 'song' -and $file.Name -match '^song-(\d+)\.html$') {
            $item = New-Metadata ([long]$Matches[1]) $relative
            $heading = Get-MatchValue $html '<h1\b[^>]*>(.*?)</h1>'
            $item.name = Get-Text (Get-MatchValue $heading '^(.*?)(?:<br\b|<span\b|$)')
            $strap = Get-MatchValue $heading '<span\b[^>]*class="strap"[^>]*>(.*?)</span>'
            $creator = @(Get-Links $strap)
            if ($creator.Count -gt 0) { $item.creator = $creator[0].Text }
            $item.date_created = Get-ArchiveDate (Get-Text $strap)
            $data = Get-MatchValue $html '<span\b[^>]*class="songdata"[^>]*>(.*?)</span>'
            $item.style = Get-Text (Get-MatchValue $data '<b>Style:</b>(.*?)(?:<br\b|$)')
            $item.avr = Get-MatchValue (Get-Text $data) '\bRating:\s*(\d+(?:\.\d+)?)'
            Merge-Metadata $catalogs.song $item
        }
        foreach ($link in @(Get-Links $html)) {
            $url = $link.Attributes['data-download-url']
            $id = Get-Id $url $kind
            if ($null -eq $id) { continue }
            $key = [string]$id
            $filename = Get-DownloadFilename $url
            if ($filename -and $catalogs[$kind].ContainsKey($key)) {
                $catalogs[$kind][$key].filename = $filename
            }
            $href = $link.Attributes['href']
            if ($href -and $href -notmatch '^[a-z]+:|^//|^#') {
                $local = [IO.Path]::GetFullPath((Join-Path $file.DirectoryName ([Uri]::UnescapeDataString($href) -replace '/', '\')))
                $linkedFiles[$kind][$local] = $id
            }
        }
    }
}

$extensions = @{
    '.rps' = 'song'; '.rns' = 'song'; '.zyp' = 'refill'; '.cmb' = 'refill'
    '.xwv' = 'refill'; '.rx7' = 'refill'; '.sm4' = 'refill'; '.rx2' = 'refill'; '.rex' = 'refill'
}
$files = @(Get-ChildItem -LiteralPath $SourceRoot -Recurse -File |
    Where-Object { $extensions.ContainsKey($_.Extension.ToLowerInvariant()) } | Sort-Object FullName)
$rows = @{ song = [Collections.Generic.List[object]]::new(); refill = [Collections.Generic.List[object]]::new() }
$inventory = [Collections.Generic.List[object]]::new()

foreach ($file in $files) {
    $kind = $extensions[$file.Extension.ToLowerInvariant()]
    $relative = $file.FullName.Substring($SourceRoot.Length + 1)
    $id = Get-Id $file.Name $kind
    $matchedBy = if ($null -ne $id) { 'id' } else { 'none' }
    if ($null -eq $id -and $linkedFiles[$kind].ContainsKey($file.FullName)) {
        $id = $linkedFiles[$kind][$file.FullName]
        $matchedBy = 'html-link'
    }
    if ($null -eq $id) {
        $originalName = $file.Name -replace '^(?:song|refill)-[0-9a-f]+-[0-9a-f]{16}-', ''
        $candidates = @($catalogs[$kind].Values | Where-Object { $_.filename -and $_.filename -ieq $originalName })
        if ($candidates.Count -eq 1) { $id = $candidates[0].Id; $matchedBy = 'unique-filename' }
    }
    $item = if ($null -ne $id -and $catalogs[$kind].ContainsKey([string]$id)) {
        $catalogs[$kind][[string]$id].Clone()
    } else {
        New-Metadata 0 $null
    }
    $objectPath = ($kind + 's/' + ($relative -replace '\\', '/'))
    $destination = Join-Path $OutputDirectory ($objectPath -replace '/', '\')
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
    $sourceHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if (-not (Test-Path -LiteralPath $destination) -or
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) {
        Copy-Item -LiteralPath $file.FullName -Destination $destination
    }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) {
        throw "Copied file checksum mismatch: $destination"
    }
    $item.archive_key = 'file:' + ($relative -replace '\\', '/')
    $item.Id = $id
    $item.filename = $file.Name
    $item.extension = $file.Extension.TrimStart('.').ToLowerInvariant()
    $item.filesize = $file.Length
    $item.s3_uri = $S3Prefix + '/' + $objectPath
    $item.source_path = $relative
    $uri = $null
    if (-not [Uri]::TryCreate($item.s3_uri, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 's3' -or -not $uri.Host -or $uri.AbsolutePath -eq '/' -or
        $uri.Query -or $uri.Fragment -or
        [Text.Encoding]::UTF8.GetByteCount($objectPath) -gt 1024) {
        throw "Invalid S3 destination for local file: $($item.s3_uri)"
    }
    $rows[$kind].Add($item)
    $inventory.Add([pscustomobject]@{
        kind = $kind; source_path = $relative; collected_path = $objectPath
        archive_id = $id; matched_by = $matchedBy; has_metadata = ($null -ne $item.metadata_source)
        filesize = $file.Length; sha256 = $sourceHash; s3_uri = $item.s3_uri
    })
}

$excludedKeys = @{ song = [Collections.Generic.List[string]]::new(); refill = [Collections.Generic.List[string]]::new() }
$includedKeys = @{}
foreach ($kind in @('song', 'refill')) {
    $uniqueRows = [Collections.Generic.List[object]]::new()
    foreach ($group in @($rows[$kind] | Group-Object { $_.filename })) {
        $ranked = @($group.Group | Sort-Object @{
            Expression = {
                $record = $_
                @(@('name', 'creator', 'style', 'date_created', 'avr', 'Id') | Where-Object {
                    $null -ne $record[$_] -and $record[$_] -ne ''
                }).Count
            }
            Descending = $true
        }, @{ Expression = { $_.source_path } })
        $uniqueRows.Add($ranked[0])
        $includedKeys[$ranked[0].archive_key] = $true
        foreach ($duplicate in @($ranked | Select-Object -Skip 1)) {
            $excludedKeys[$kind].Add($duplicate.archive_key)
        }
    }
    $rows[$kind] = @($uniqueRows | Sort-Object { $_.archive_key })
    foreach ($item in $rows[$kind]) {
        $item.filename = $item.filename -creplace '^(song|refill)-[a-zA-Z0-9]+-[a-zA-Z0-9]+-', ''
    }
}
foreach ($item in $inventory) {
    $key = 'file:' + ($item.source_path -replace '\\', '/')
    $item | Add-Member -NotePropertyName included_in_sql -NotePropertyValue $includedKeys.ContainsKey($key)
}

function Get-SqlValue($Value) {
    if ($null -eq $Value -or ($Value -is [string] -and $Value -eq '')) { return 'NULL' }
    $text = [string]$Value
    if ($text.Contains([char]0)) { throw 'PostgreSQL text cannot contain NUL characters.' }
    "'" + $text.Replace("'", "''") + "'"
}

$schemaPath = Join-Path $PSScriptRoot '..\src\main\resources\db\migration\V10__archive_catalogs.sql'
$dataMarker = '-- BEGIN GENERATED ARCHIVE DATA'
$schema = ([IO.File]::ReadAllText($schemaPath) -split [regex]::Escape($dataMarker), 2)[0].TrimEnd()
$migrationInserts = [Text.StringBuilder]::new()
$sql = [Text.StringBuilder]::new()
[void]$sql.AppendLine('-- Generated by scripts\Collect-Archive.ps1. Local files only; no uploads have been performed.')
[void]$sql.AppendLine('-- s3_uri is the intended destination, not evidence that an object has been uploaded.')
[void]$sql.AppendLine("SET client_encoding = 'UTF8';")
[void]$sql.AppendLine('BEGIN;')
[void]$sql.AppendLine('SET LOCAL standard_conforming_strings = on;')
[void]$sql.AppendLine($schema)
foreach ($kind in @('song', 'refill')) {
    $idColumn = if ($kind -eq 'song') { 'archive_song_id' } else { 'archive_refill_id' }
    $columns = @('archive_key', $idColumn, 'name', 'creator')
    if ($kind -eq 'song') { $columns += 'style' }
    $columns += 'date_created'
    if ($kind -eq 'song') { $columns += 'avr' }
    $columns += @('filename', 'extension', 'filesize', 's3_uri', 'source_path', 'metadata_source')
    $updates = @($columns | Where-Object { $_ -ne 'archive_key' } | ForEach-Object { "$_ = EXCLUDED.$_" }) -join ', '
    foreach ($item in $rows[$kind]) {
        $values = @($columns | ForEach-Object {
            if ($_ -eq $idColumn) { Get-SqlValue $item.Id } else { Get-SqlValue $item[$_] }
        }) -join ', '
        $insert = "INSERT INTO archive_$($kind)s ($($columns -join ', ')) VALUES ($values) ON CONFLICT (archive_key) DO UPDATE SET $updates;"
        [void]$sql.AppendLine($insert)
        [void]$migrationInserts.AppendLine($insert)
    }
    [void]$sql.AppendLine("DELETE FROM archive_$($kind)s WHERE archive_key LIKE 'metadata:%' AND source_path IS NULL;")
    if ($excludedKeys[$kind].Count -gt 0) {
        $keys = @($excludedKeys[$kind] | ForEach-Object { Get-SqlValue $_ }) -join ', '
        [void]$sql.AppendLine("DELETE FROM archive_$($kind)s WHERE archive_key IN ($keys);")
    }
}
[void]$sql.AppendLine('COMMIT;')
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($SqlPath))
[IO.File]::WriteAllText($SqlPath, $sql.ToString(), [Text.UTF8Encoding]::new($false))
if ($UpdateMigration) {
    $migration = $schema + "`r`n`r`n" + $dataMarker + "`r`n" + $migrationInserts.ToString() + "-- END GENERATED ARCHIVE DATA`r`n"
    [IO.File]::WriteAllText($schemaPath, $migration, [Text.UTF8Encoding]::new($false))
    Write-Output "Migration updated: $schemaPath"
}
$inventoryPath = Join-Path $OutputDirectory 'inventory.json'
[IO.File]::WriteAllText($inventoryPath, (ConvertTo-Json -InputObject @($inventory.ToArray()) -Depth 4), [Text.UTF8Encoding]::new($false))
foreach ($kind in @('song', 'refill')) {
    $local = @($inventory | Where-Object { $_.kind -eq $kind })
    $missingMetadata = @($local | Where-Object { -not $_.has_metadata }).Count
    Write-Output "$kind`: $($htmlCounts[$kind]) HTML pages, $($local.Count) collected files, $($rows[$kind].Count) unique-filename rows, $($excludedKeys[$kind].Count) duplicates excluded, $missingMetadata files without matching metadata."
}
Write-Output "Collected files: $OutputDirectory"
Write-Output "SQL: $SqlPath"
