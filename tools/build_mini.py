"""Build the small starter catalog bundled in the APK.

The app downloads the full media-index catalogue on first launch and refreshes it daily; this file
only has to make the first launch (and offline use) useful. It keeps the most popular titles plus
everything airing or announced, without episode lists, and trims synopses.

Usage:
    python tools/build_mini.py                 # from the media-index CDN
    python tools/build_mini.py path/to/catalog.db   # from an older full catalog.db (adds banners)
"""
import gzip, json, sqlite3, sys, urllib.request
from pathlib import Path

BASE = "https://cdn.jsdelivr.net/gh/sanilili/media-index@main/data"
TOP = 2500
SYNOPSIS_CHARS = 280
target = Path(__file__).resolve().parents[1] / "app/src/main/assets/catalog-mini.json.gz"


def fetch(name):
    with urllib.request.urlopen(f"{BASE}/{name}", timeout=60) as r:
        return json.loads(r.read())


def short(text):
    text = (text or "").replace("<br>", " ").replace("<br/>", " ").replace("<i>", "").replace("</i>", "").strip()
    return text if len(text) <= SYNOPSIS_CHARS else text[:SYNOPSIS_CHARS].rsplit(" ", 1)[0] + "…"


rows = []
if len(sys.argv) > 1:
    db = sqlite3.connect(sys.argv[1])
    db.row_factory = sqlite3.Row
    for r in db.execute("SELECT * FROM titles"):
        rows.append(dict(id=r["id"], mal=r["mal"], title=r["title"], poster=r["poster"], banner=r["banner"] or "",
                         year=r["year"], status=r["status"], format=r["format"], episodes=r["episodes"],
                         score=r["score"], popularity=r["popularity"], genres=r["genres"], synopsis=short(r["synopsis"])))
else:
    catalog = fetch("index.json")
    mapping = fetch("id-map.json")
    for item in catalog:
        rows.append(dict(id=item["id"], mal=mapping.get(str(item["id"]), {}).get("mal"), title=item.get("title"),
                         poster=item.get("poster"), banner="", year=item.get("year"), status=item.get("status"),
                         format=item.get("format"), episodes=item.get("episodes"), score=item.get("score"),
                         popularity=item.get("popularity", 0), genres=", ".join(item.get("genres", [])), synopsis=""))

rows.sort(key=lambda r: r["popularity"] or 0, reverse=True)
keep = rows[:TOP] + [r for r in rows[TOP:] if r["status"] in ("RELEASING", "NOT_YET_RELEASED")]
# Short keys keep the file small; Catalog.kt reads the same names.
out = [[r["id"], r["mal"], r["title"], r["poster"], r["banner"], r["year"], r["status"], r["format"],
        r["episodes"], r["score"], r["popularity"], r["genres"], r["synopsis"]] for r in keep]
target.parent.mkdir(parents=True, exist_ok=True)
with gzip.open(target, "wt", encoding="utf-8", compresslevel=9) as f:
    json.dump({"fields": ["id", "mal", "title", "poster", "banner", "year", "status", "format", "episodes", "score", "popularity", "genres", "synopsis"], "rows": out}, f, ensure_ascii=False, separators=(",", ":"))
print(f"{len(out)} titles -> {target} ({target.stat().st_size // 1024} KiB)")
