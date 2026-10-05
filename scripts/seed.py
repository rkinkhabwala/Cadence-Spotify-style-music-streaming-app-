#!/usr/bin/env python3
"""Seeds Cadence through its real admin API (spec 8): 5 artists, 10 albums, genres and ~20 tracks.

Audio comes from ./seed-audio/ (mp3/flac/wav/m4a). If that folder is empty, 20 short, distinct tones are
generated with FFmpeg first. Every track goes through the production upload flow (presigned PUT → upload-complete →
transcoder) and the script waits until all of them are READY. Also creates a demo listener.

Needs the API and the transcoder running (`make up` + `make app`, or `make run-api` + `make run-transcoder`).
Standard library only. Configuration comes from .env (CADENCE_ADMIN_*, CADENCE_DEMO_*, CADENCE_API_URL).
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AUDIO_DIR = ROOT / "seed-audio"
FORMATS = {"mp3": "audio/mpeg", "flac": "audio/flac", "wav": "audio/wav", "m4a": "audio/mp4"}

# 5 artists x 2 albums x 2 tracks. "The Beatlz" doubles as the fuzzy-search fixture for Phase 2: typing "beatls"
# must suggest it, which only works through fuzzy matching (one substituted letter).
FIXTURE_ARTIST = "The Beatlz"
LEGACY_FIXTURE_ARTIST = "The Beatles Revival"  # name used by Phase 1 seeds
CATALOG = [
    (FIXTURE_ARTIST, "Four-piece tribute to sixties pop", [("Abbey Lane", "1969-09-26", ["Rock", "Pop"]),
                                                                    ("Yellow Harbor", "1966-08-05", ["Rock"])]),
    ("Nova Lights", "Synthwave duo from Lisbon", [("Neon Tides", "2021-03-12", ["Synthwave", "Electronic"]),
                                                  ("Afterglow", "2023-11-03", ["Synthwave"])]),
    ("Echo Harbor", "Ambient soundscapes", [("Low Tide", "2019-05-17", ["Ambient"]),
                                            ("Fog Signals", "2022-01-21", ["Ambient", "Electronic"])]),
    ("Velvet Static", "Lo-fi jazz collective", [("Late Trains", "2020-10-09", ["Jazz", "Lo-fi"]),
                                                ("Window Seat", "2024-02-16", ["Lo-fi"])]),
    ("Lumen Drift", "Instrumental post-rock", [("Slow Satellites", "2018-07-27", ["Post-rock"]),
                                               ("Field Notes", "2025-04-04", ["Post-rock", "Ambient"])]),
]


def load_env():
    env = {}
    for name in (".env", ".env.example"):
        path = ROOT / name
        if path.exists():
            for line in path.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    env.setdefault(key.strip(), value.strip())
    env.update(os.environ)
    return env


ENV = load_env()
API = ENV.get("CADENCE_API_URL", f"http://localhost:{ENV.get('CADENCE_API_PORT', '8080')}").rstrip("/")


def call(method, path, body=None, token=None, expect=(200, 201, 202, 204)):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(API + path, data=data, method=method)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        raw, status = error.read(), error.code
    payload = json.loads(raw) if raw else None
    if status not in expect:
        sys.exit(f"{method} {path} -> {status}: {payload}")
    return status, payload


def ensure_audio():
    files = sorted(p for p in AUDIO_DIR.iterdir() if p.suffix.lower().lstrip(".") in FORMATS) if AUDIO_DIR.exists() else []
    if files:
        print(f"Using {len(files)} file(s) from {AUDIO_DIR}")
        return files
    print("seed-audio/ is empty: generating 20 distinct tones with FFmpeg")
    AUDIO_DIR.mkdir(exist_ok=True)
    notes = [261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25, 587.33, 659.25]
    exts = ["mp3", "mp3", "mp3", "flac", "mp3", "wav", "mp3", "m4a", "mp3", "mp3"]
    generated = []
    for i in range(20):
        root, fifth = notes[i % 10], notes[i % 10] * 1.5
        seconds = 20 + (i * 7) % 26                     # 20..45 s
        ext = exts[i % 10]
        out = AUDIO_DIR / f"{i + 1:02d}-tone-{int(root)}hz.{ext}"
        codec = {"mp3": ["-c:a", "libmp3lame", "-b:a", "192k"], "flac": ["-c:a", "flac"],
                 "wav": ["-c:a", "pcm_s16le"], "m4a": ["-c:a", "aac", "-b:a", "192k"]}[ext]
        subprocess.run(["ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                        "-f", "lavfi", "-i", f"sine=frequency={root}:duration={seconds}",
                        "-f", "lavfi", "-i", f"sine=frequency={fifth}:duration={seconds}",
                        "-filter_complex", f"amix=inputs=2,tremolo=f={2 + i % 5}:d=0.{3 + i % 5},"
                                           f"afade=t=in:d=1,afade=t=out:st={seconds - 2}:d=2",
                        "-ac", "2", "-ar", "44100", *codec, str(out)], check=True)
        generated.append(out)
    return generated


def title_of(path):
    stem = path.stem.split("-", 1)[-1] if path.stem[:2].isdigit() else path.stem
    return stem.replace("-", " ").replace("_", " ").title()


def upload(token, track_id, path):
    ext = path.suffix.lower().lstrip(".")
    size = path.stat().st_size
    _, url = call("POST", f"/api/v1/admin/tracks/{track_id}/upload-url", {"extension": ext, "sizeBytes": size}, token)
    put = urllib.request.Request(url["uploadUrl"], data=path.read_bytes(), method="PUT")
    put.add_header("Content-Type", url["headers"]["Content-Type"])
    with urllib.request.urlopen(put, timeout=120) as response:
        if response.status != 200:
            sys.exit(f"Upload of {path.name} failed with {response.status}")
    call("POST", f"/api/v1/admin/tracks/{track_id}/upload-complete", None, token)


def admin_titles(token):
    titles, cursor = set(), None
    while True:
        _, page = call("GET", "/api/v1/admin/tracks?limit=100" + (f"&cursor={cursor}" if cursor else ""), None, token)
        titles.update(t["title"] for t in page["items"])
        cursor = page["nextCursor"]
        if not cursor:
            return titles


def rename_legacy_fixture(token):
    """Catalogs seeded in Phase 1 have the fixture artist under its old name; rename it in place."""
    album_ids, cursor = set(), None
    while True:
        _, page = call("GET", "/api/v1/admin/tracks?limit=100" + (f"&cursor={cursor}" if cursor else ""), None, token)
        album_ids.update(t["albumId"] for t in page["items"])
        cursor = page["nextCursor"]
        if not cursor:
            break
    for album_id in album_ids:
        _, album = call("GET", f"/api/v1/albums/{album_id}")
        if album["artist"]["name"] == LEGACY_FIXTURE_ARTIST:
            call("PATCH", f"/api/v1/admin/artists/{album['artist']['id']}", {"name": FIXTURE_ARTIST}, token)
            print(f"Renamed artist '{LEGACY_FIXTURE_ARTIST}' to '{FIXTURE_ARTIST}' (fuzzy-search fixture)")
            return


def main():
    force = "--force" in sys.argv
    try:
        call("GET", "/actuator/health")
    except (urllib.error.URLError, ConnectionError) as error:
        sys.exit(f"API not reachable at {API} ({error}). Start it with `make app` or `make run-api` (+ transcoder).")

    _, login = call("POST", "/api/v1/auth/login",
                    {"email": ENV["CADENCE_ADMIN_EMAIL"], "password": ENV["CADENCE_ADMIN_PASSWORD"]})
    token = login["accessToken"]
    files = ensure_audio()
    titles = [title_of(p) for p in files]
    if not force and set(titles) <= admin_titles(token):
        rename_legacy_fixture(token)
        print("Catalog already seeded (use `make seed ARGS=--force` to add another copy).")
        return

    albums = []
    for name, bio, album_specs in CATALOG:
        _, artist = call("POST", "/api/v1/admin/artists", {"name": name, "bio": bio, "verified": True}, token)
        for title, released, genres in album_specs:
            _, album = call("POST", "/api/v1/admin/albums", {"title": title, "artistId": artist["id"],
                                                             "releaseDate": released, "type": "ALBUM",
                                                             "genres": genres}, token)
            albums.append(album)
    print(f"Created {len(CATALOG)} artists and {len(albums)} albums")

    pending = {}
    for i, (path, title) in enumerate(zip(files, titles)):
        album = albums[i % len(albums)]
        _, track = call("POST", "/api/v1/admin/tracks",
                        {"title": title, "albumId": album["id"], "trackNumber": i // len(albums) + 1}, token)
        upload(token, track["id"], path)
        pending[track["id"]] = title
        print(f"  uploaded {path.name:<32} -> {album['title']}")

    print(f"Waiting for {len(pending)} track(s) to be transcoded ...")
    deadline, failed = time.time() + 600, {}
    while pending and time.time() < deadline:
        for track_id in list(pending):
            _, track = call("GET", f"/api/v1/admin/tracks/{track_id}", None, token)
            if track["status"] == "READY":
                pending.pop(track_id)
            elif track["status"] == "FAILED":
                failed[track_id] = (pending.pop(track_id), track["failureReason"])
        time.sleep(1)
    if pending or failed:
        sys.exit(f"Not READY: {list(pending.values())}; FAILED: {list(failed.values())}")
    print(f"All {len(files)} tracks READY.")

    demo_email = ENV.get("CADENCE_DEMO_EMAIL", "demo@cadence.local")
    demo_password = ENV.get("CADENCE_DEMO_PASSWORD")
    if demo_password:
        status, _ = call("POST", "/api/v1/auth/register", {"email": demo_email, "password": demo_password,
                                                            "displayName": "Demo Listener"}, expect=(201, 409))
        print(f"Demo listener {demo_email} {'created' if status == 201 else 'already exists'}")
    print(f"Try it: {API}/dev/player.html (log in as the admin to list READY tracks)")


if __name__ == "__main__":
    main()
