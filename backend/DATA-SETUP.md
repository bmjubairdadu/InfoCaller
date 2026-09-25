# Server-side data setup

Nothing sensitive lives in the APK any more. The app only ever asks the backend.

## NID / DOB database

`database.json.gz` is a gzip-compressed JSON array:

```json
[
  { "number": "01616382033", "nid": "7756684226", "dob": "1969-04-19" }
]
```

Optional enrichment fields are also read if present: `nameEn`, `nameBn`, `fatherName`,
`motherName`, `address`, `photoUrl`.

### Option A — local file (easiest for testing)

```bash
cp ../server-data/database.json.gz ./data/database.json.gz
```

```
NID_DATA_PATH=./data/database.json.gz
```

### Option B — private GitHub repo (production)

Put `database.json.gz` in a **private** repo, then point at the contents API:

```
NID_DATA_URL=https://api.github.com/repos/<owner>/<repo>/contents/<path-to-file>
```

`GITHUB_TOKEN` must have `repo` read access. Files over 1 MB are fetched through the
Git Blobs API automatically, so the gzipped file needs no special handling.

First request loads the file and builds an in-memory index. Large files take a few
seconds once; after that lookups are instant until the process restarts.

### Verify

```bash
curl -H "x-api-key: $INFOCALLER_API_KEY" http://localhost:3000/api/v1/nid/phone/01616382033
```

Returns the record, or `404` if the number is not in the database.

## Apify

`APIFY_TOKEN`, `APIFY_TOKEN_2` and `APIFY_TOKEN_3` are used in order. A key that
returns 401/403/429 is skipped and the next one is used, so you can rotate keys
without downtime. `/api/v1/lookup/phone` is the app's last-resort call — it only
runs when no other source produced a profile photo.

## Shared caller registry

`GITHUB_REPO` stores one JSON file per number under `registry/numbers/<e164>.json`.
Reads are open; writes need `x-api-key`. Contributing also requires the user to
have turned on the in-app toggle.
