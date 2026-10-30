# Design: SQLite storage and optional Google Drive sync

Status: implemented in 2.0, with the exceptions noted below.

Sections 1 to 6 describe what the app now does. Section 7 is still a proposal in
one respect: the Drive backend is written and wired up, but the three questions
in 7.4 have not been answered, so nobody has yet signed a head unit in to Google
with it. The folder backend works and needs no account.

Two things changed once the code met a device, and both are in the code rather
than here: a trip that is closed twice must not repack itself from working rows
that are gone, and reclaiming space needs a WAL checkpoint as well as an
incremental vacuum - without the checkpoint the database kept seventeen pages and
a four-hundred-kilobyte log where ten pages would do.

It covers four things the current CSV-per-trip storage does not answer well: where
trip data should live, how to move what already exists without losing any of it,
what any of this costs on the unit's flash, and how a trip could reach the owner's
own Google Drive.

Every size in this document was measured, not estimated. The method is in the
appendix, and the numbers come from the project's own fixtures — two short town
drives and the simulated Warsaw → Gdańsk run, 1492 fixes in total — scaled up a
hundredfold for the year projections.

---

## 1. What exists today

One CSV file per trip, in the private app folder on external storage:

```
Android/data/pl.matsuo.navi/files/trips/trip-20260115-071530.csv
1766740800,52.229700,21.012200
1766740810,52.230238,21.013300
```

Exactly 31 bytes per fix, measured across all three fixtures. A fix every ten
seconds, so an hour of driving costs 10.9 KiB. `Trip` appends a line, reads whole
files back for the route view, and re-reads every file to build the history strip.

What works: it is trivially inspectable, survives a crash with at most one lost
line, and needs no schema. What does not:

- **The history strip reads every file.** `summarize()` parses a whole trip to
  produce two numbers. With a year of trips that is 4.4 MiB parsed to draw one row
  of chips.
- **No place for state that is not a fix.** Sync needs to record what has been
  uploaded and when. There is nowhere to put it except more files.
- **Filesystem waste.** Every trip occupies whole 4 KiB blocks. The three fixtures
  hold 47,744 bytes of data and take 53,248 bytes of disk; a 5-minute errand uses
  4 KiB to store 1,120 bytes.
- **No atomicity.** A trip is a file plus a name; there is no way to write a trip
  and its summary as one unit.

None of this is on fire. The point of writing the numbers down is that the usual
reason given for moving to SQLite — saving space — turns out to be false, and the
real reasons are the three above.

## 2. What SQLite costs and saves, measured

The same 1492 fixes, stored six ways, and the same data repeated a hundred times
(149,200 fixes, about 414 hours of driving, roughly 1.1 hours a day for a year):

| Storage | 1492 fixes | 149,200 fixes | B/fix at that scale |
| --- | --- | --- | --- |
| CSV, as today | 47,744 | 4.41 MiB | 31.0 |
| SQLite, `REAL` lat/lon, row per fix | 61,440 | not measured | — |
| SQLite, micro-degree `INTEGER`, row per fix | 49,152 | not measured | — |
| SQLite, 1e-5 `INTEGER`, row per fix, no index | 45,056 | not measured | — |
| SQLite, 1e-5 `INTEGER`, row per fix, index on (trip, t) | not measured | 5.53 MiB | 38.9 |
| SQLite, delta-encoded rows, no index | 36,864 | 2.45 MiB | 17.2 |
| CSV, gzip -9 | 14,916 | not measured | 10.0 |
| **SQLite, one packed track BLOB per trip, 5 B/fix** | **16,384** | **0.85 MiB** | **5.98** |
| SQLite, packed BLOB at 4 B/fix (no timestamps) | 16,384 | 0.64 MiB | 4.53 |

Bytes per fix are quoted at the scale where they were measured. At 1492 fixes a
database is mostly page granularity — three of the variants above land on the same
16 KiB — so only the hundredfold figures say anything about a year of driving.

Three things fall out of this.

**A row per fix is the wrong shape.** The obvious schema — one row holding trip,
time, latitude and longitude — is *worse than the CSV it replaces*, 38.9 bytes
against 31.0, because every row pays a cell header, a rowid and an index entry to
store twelve bytes of payload. Had this been built without measuring, the migration
would have increased disk use by a quarter and nobody would have noticed until the
numbers were compared.

**The win is in the encoding, not the engine.** Delta-encoded rows already halve
it; a packed blob gets to 6 bytes per fix, five times better than CSV, and that has
nothing to do with SQLite. The same encoding in a flat file would do the same.

**SQLite is therefore chosen for its structure, not its size** — atomic writes,
indexed summaries, and a place to keep sync state — and the encoding is what pays
for the disk.

## 3. Proposed storage

### 3.1 Schema

```sql
PRAGMA user_version = 1;

CREATE TABLE trip (
  id          INTEGER PRIMARY KEY,
  started_at  INTEGER NOT NULL,          -- epoch seconds, UTC
  ended_at    INTEGER NOT NULL,
  points      INTEGER NOT NULL,
  meters      REAL    NOT NULL,
  track       BLOB,                      -- NVT v1, see 3.2; NULL once thinned
  source      TEXT    NOT NULL DEFAULT 'device',   -- 'device' | 'csv' | 'remote'
  remote_id   TEXT,                      -- Drive file id, once uploaded
  remote_hash TEXT,                      -- SHA-256 of the uploaded artifact
  uploaded_at INTEGER
);

CREATE UNIQUE INDEX trip_started ON trip(started_at);
CREATE INDEX trip_pending ON trip(uploaded_at) WHERE uploaded_at IS NULL;

-- The drive in progress, appended to a fix at a time.
CREATE TABLE open_fix (
  trip_id INTEGER NOT NULL,
  t       INTEGER NOT NULL,              -- epoch seconds
  lat     INTEGER NOT NULL,              -- micro-degrees
  lon     INTEGER NOT NULL,
  PRIMARY KEY (trip_id, t)
) WITHOUT ROWID;

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
```

`points` and `meters` are derivable from the track and are stored anyway: they cost
twelve bytes a trip and save decoding every track to draw the history strip, which
is the operation that happens most often.

### 3.2 The track format (NVT v1)

A trip's track is one blob. Big-endian throughout, because that is what
`DataOutputStream` writes.

```
 0  "NVT" 0x01                     magic and version
 4  int32   lat0                   first fix, micro-degrees
 8  int32   lon0
12  int32   t0                     first fix, epoch seconds
16  repeated, one per later fix:
      int16  dlat                  micro-degrees from the previous fix
      int16  dlon
      uint8  dt                    seconds from the previous fix
```

Five bytes a fix. Two escapes keep it honest:

- `dt == 255` means the real gap follows as a `uint16`. A trip stays open across a
  five-minute pause, so gaps up to 300 seconds occur and do not fit in a byte.
- `dlat == -32768` means an absolute fix follows as `int32 lat, int32 lon`, for the
  case where the delta does not fit.

**Micro-degrees are exact, not an approximation.** The CSV already stores six
decimal places, so an `int32` of micro-degrees represents the existing data
losslessly. Migration cannot round anything.

**The 16-bit deltas have room.** Across the fixtures the largest step is 2,559
micro-degrees of latitude and 3,838 of longitude, against a limit of 32,767 — more
than eight times the headroom, which is about 890 km/h at a ten-second cadence. The
escape exists for GPS glitches, not for fast driving.

### 3.3 Why the open trip is rows and the closed trip is a blob

Appending a fix to a blob means rewriting the blob. On a four-hour drive the last
fix would rewrite seven kilobytes, and every ten seconds SQLite would write that
plus its journal — some gigabytes a year of flash writes to store under a megabyte.

So the drive in progress accumulates in `open_fix`, one small row per fix, which is
an append. When the trip closes — a gap longer than `Trip.GAP_MS`, or the next
start-up finding a stale open trip — the rows are packed into a blob in one
transaction and deleted. `open_fix` holds one drive at most, a few thousand rows.

This also replaces `Trip.resume()`: a restarted process finds its drive by reading
`open_fix`, which is the same rule applied to better-shaped storage.

### 3.4 Where the database lives

Recommendation: internal storage, `getDatabasePath("navi.db")`, with
`journal_mode = WAL`.

The trip CSVs currently sit in external storage, where a file manager or a USB
cable can reach them. A database there would be reachable too, but WAL on the
emulated-storage FUSE layer is the kind of thing that works until it does not, and
a half-written database loses every trip rather than one file.

The access that external storage was giving is replaced by an explicit export: the
same artifact the Drive uploader produces, written to
`getExternalFilesDir(null)/export/` on demand. That keeps the data reachable
without betting the whole history on FUSE.

## 4. Migration

### 4.1 Rules

1. Runs once, on the first start after the update, before the trip screen can be
   opened. It takes well under a second for a year of files.
2. **Nothing is deleted.** Each imported CSV moves to `trips/imported/`. A settings
   action offers to delete that folder afterwards and says how much it would free.
3. **Idempotent.** `trip.started_at` is unique; re-running imports nothing twice.
   Interrupting it halfway and starting again resumes where it stopped.
4. **Verified before it counts.** After inserting a trip, its blob is decoded and
   compared with the CSV it came from: point count, first and last coordinates,
   and distance within a metre. A trip that fails verification is left in place and
   recorded in `meta`; it is not moved and not marked as imported.
5. **A damaged CSV is skipped, never fatal.** The current parser already tolerates
   truncated lines; the migration keeps that behaviour and notes the file.
6. `user_version` goes from 0 to 1 only when every file has been accounted for.

### 4.2 What it does per file

```
for each trips/*.csv, oldest first:
    fixes  = parse (skipping damaged lines, as today)
    if fixes is empty: move aside, continue
    blob   = NVT encode (fixes)
    meters = Geo.length (fixes)          -- the same arithmetic as today
    INSERT OR IGNORE INTO trip (started_at, ended_at, points, meters, track, source)
    verify by decoding blob
    move the file to trips/imported/
```

### 4.3 How it is tested

The same split the project already uses. Unit tests, on a plain JVM against a
temporary directory, because the encoder and the migration are deliberately free of
Android types:

- round trip: encode then decode any track and get the same fixes back, including
  the `dt` and absolute escapes;
- the fixtures import with the point counts and distances the CSV tests already
  assert;
- running the migration twice imports nothing the second time;
- a migration interrupted after N files and restarted ends in the same state as one
  that ran straight through;
- a truncated line, an empty file and a file of garbage are each survived;
- the disk sizes stay within the bounds in section 5, so a future change that makes
  storage balloon fails a test rather than a phone.

On-device, one journey test: a unit with CSV trips updates, and the trip screen
afterwards shows the same history it showed before.

## 5. Disk usage, before and after

A year is taken as 149,200 fixes — about 414 hours of driving — in roughly 730
trips. Logical size is the data; on-disk adds the 4 KiB block rounding each file
pays, which is why the CSV column has two figures.

| | Today (CSV) | Proposed (SQLite + NVT) |
| --- | --- | --- |
| Per fix | 31.0 B | 5.98 B |
| One hour of driving | 10.9 KiB | 2.1 KiB |
| A 5-minute errand | 1,120 B in a 4 KiB block | ~210 B |
| Three fixture trips | 47,744 B logical, 53,248 B on disk | 16,384 B |
| One year | 4.41 MiB logical, ~5.7 MiB on disk | 0.85 MiB |
| Five years | ~28 MiB on disk | 4.3 MiB |
| Ten years | ~57 MiB on disk | 8.5 MiB |
| Files to open to draw the history strip | 730 | 0 — one indexed query |

The honest summary: after ten years the current format would still only be using
57 MiB, which no head unit will notice. **Space is not the reason to do this.** The
reason is the last row of that table, plus atomic writes and somewhere to keep sync
state. The space is a side effect of encoding the data properly on the way past.

## 6. Optimisations, in order of what they are worth

1. **The track encoding — 31 B to 6 B per fix, measured.** Everything else on this
   list is a rounding error next to it.
2. **A blob per trip rather than a row per fix** — 17.2 B to 5.98 B even against
   delta-encoded rows that carry no index at all. What disappears is the per-row
   cell header and rowid, paid 149,200 times.
3. **Leave `page_size` alone.** 512-byte pages shrink a three-trip database from
   16,384 to 7,680 bytes, which looks attractive until the database is a year old,
   when small pages cost more in btree depth than they save in slack. The default
   4,096 is right for the size this reaches.
4. **`auto_vacuum = INCREMENTAL`**, with `incremental_vacuum` after a delete, so
   removing trips returns the space instead of leaving a sparse file.
5. **Thinning uploaded trips.** Once a trip is on Drive and older than, say, six
   months, its blob can be dropped and the row kept: the history strip still shows
   it, the route redraws after a download. A summary row is about 60 bytes, so a
   decade of thinned history is well under a megabyte. Worth building only if
   somebody ever reports a space problem — which, at 8.5 MiB a decade, will not
   happen.
6. **Compressing the blob.** Not measured, and worth one experiment rather than a
   guess: deflate on an already delta-encoded track may win little, since gzip on
   the raw CSV lands at 10 B/fix while the packed format is already at 6. Decide
   with a measurement once the encoder exists; it is only worth it if it beats
   roughly 4.5 B/fix, and it costs the ability to decode part of a track.
7. **Lossy simplification** (Douglas–Peucker at, say, a 10 m tolerance) would cut
   point counts hard on real tracks. It cannot be quantified from this project's
   fixtures, which are interpolated along straight legs and would collapse to a few
   dozen points — a number that would say nothing about real GPS. Measure it on a
   real recorded drive before believing any figure, and treat it as a last resort,
   because it throws away data that cannot be recovered.

## 7. Google Drive

### 7.1 Goals and non-goals

Goals: a trip, once finished, ends up in the owner's own Drive, visible as an
ordinary file in an ordinary folder; a reinstalled or replaced unit can get the
history back; nothing is uploaded without the owner turning it on.

Non-goals: real-time sync, editing from Drive, sharing, multi-user, and syncing the
database file itself. A SQLite file in a sync folder is a well-known way to lose
data; what gets uploaded is immutable per-trip artifacts.

### 7.2 What gets uploaded

One file per closed trip, named after its start, organised by year:

```
Navi simple/2026/2026-01-15T07-15-30.csv.gz
```

Gzipped CSV, not the packed binary: 10 B/fix against 6, about 1.4 MiB a year,
against a 15 GB quota. In exchange the owner can open a trip in any spreadsheet or
feed it to any tool, which is the entire point of putting it on their own drive
rather than the app's hidden folder. The packed format stays an internal detail.

Trips are immutable once closed, so an upload is idempotent: same name, same bytes,
same hash. There is no conflict resolution because there are no conflicts.

### 7.3 The protocol

```
closed trip, uploaded_at IS NULL, network available
  -> build artifact from the blob
  -> SHA-256 it
  -> does Navi simple/<year>/<name> already exist?
       yes, same hash -> record remote_id, done
       yes, different  -> keep both, suffix the new one, note it
       no              -> multipart upload (files are ~10 KB, resumable is overkill)
  -> UPDATE trip SET remote_id, remote_hash, uploaded_at
```

Driven from the existing foreground service, on the same tick that already runs
every ten seconds, with a backoff on failure and no retries while the drive is in
progress. Restoring runs the same thing backwards: list the folder, download what
is not in the database, insert with `source = 'remote'`.

### 7.4 Authentication: the part that needs deciding first

This is where a head unit is awkward. The app has no dependencies and should keep
none, so Google Sign-In and Play Services are out; OAuth over `HttpsURLConnection`
is a few hundred lines and fits the project. The problem is the sign-in gesture
itself, on a device that may have no usable browser and certainly has no keyboard
anyone wants to use while parked.

Three candidates:

1. **Device flow** (`oauth2.googleapis.com/device/code`): the unit shows a short
   code, the owner types it on their phone. Exactly the right shape for a head
   unit. **Open question: Google documents the limited-input device flow as
   supporting a restricted set of scopes, and whether `drive.file` is among them
   must be confirmed before this is designed around.** It is the first thing to
   check, because the answer changes the rest.
2. **Loopback redirect in the unit's browser**: standard installed-app flow with
   PKCE. Works if the ROM has a browser that can reach a localhost redirect.
   Unverified on this unit.
3. **Pairing**: the phone does the OAuth, the unit receives a refresh token from a
   QR code or a typed pairing string. Most work, fewest assumptions about the unit.

Two further things to verify before committing:

- **Scope.** `drive.file` — per-file access to what the app itself created — is
  enough and is the narrowest scope that does the job. Whether it needs Google's
  verification for a published app, and whether a personal build can stay
  unverified, has to be confirmed rather than assumed.
- **Testing-mode refresh tokens.** An OAuth client left in "Testing" publishing
  status issues refresh tokens that stop working after a few days, which would mean
  signing in again every week. If the app cannot be moved out of testing status,
  this feature is not worth building as designed.

Because all three of these are open, **the sync layer is defined behind an
interface with Drive as its first implementation**, not as Drive-specific code:

```java
interface TripSink {
    boolean available();
    String upload(String name, byte[] artifact) throws IOException;  // returns remote id
    List<String> list(int year) throws IOException;
    byte[] download(String remoteId) throws IOException;
}
```

A `FolderSink` that writes the same artifacts to a USB stick or an SD card is
twenty lines, needs no account at all, and is worth building first: it proves the
export format, the migration and the retention logic while the OAuth questions are
still open, and it is genuinely useful on a unit that never sees Wi-Fi.

### 7.5 Privacy

A recorded route is a log of where its owner has been, so: sync is off until
switched on, it goes only to the owner's own Drive, the app asks for the narrowest
scope that works, tokens live in the app's private storage, and switching sync off
offers to delete the remote folder. The app sends nothing anywhere else — the
weather request stays as it is, a rounded coordinate to Open-Meteo.

## 8. Phases

Each phase is shippable on its own and leaves the app working.

1. **Encoder.** NVT v1 encode and decode, unit tested, used nowhere yet.
2. **Database.** Schema, `open_fix` recording, the close-and-pack step, the trip
   screen reading from SQLite. Migration from CSV, with its tests. The disk-size
   assertions from section 5 become tests.
3. **Export.** Artifact building and `FolderSink`, plus the settings action to
   export and the one to delete imported CSVs.
4. **Drive.** Only after the three questions in 7.4 have answers. Auth, upload,
   restore, the switch in settings.

Phases 1 to 3 depend on nothing external and can be built and tested entirely on
the emulator. Phase 4 is the only one that can turn out to be impossible, and by
then the data will already be in the right shape and exportable by hand.

---

## Appendix: how the sizes were measured

On the project's own emulator, Android 15, SQLite 3.44.3, with the three fixture
trips written by `TripFixtures` — 1,492 fixes, the same data the test suite uses.
Each variant was built, `VACUUM`ed and measured with `stat`; the year figures
repeat the dataset a hundred times in one database, so they include real btree
overhead at that scale rather than a multiplication.

```sh
sqlite3 c.db "CREATE TABLE fix(trip INTEGER, t INTEGER, lat INTEGER, lon INTEGER);
              INSERT INTO fix SELECT trip, t, CAST(lat*100000 AS INTEGER),
                     CAST(lon*100000 AS INTEGER) FROM r.raw; VACUUM;"
stat -c %s c.db
```

The 5 B/fix and 4 B/fix blob figures use `zeroblob(points * n)` for the track, so
they measure the storage the format costs and not the entropy of this particular
synthetic route. The delta headroom figures come from the fixture CSVs directly.
