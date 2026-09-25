import ijson, os, json, collections

SRC = "polar-region/iodb.json"
DST = "polar-point/iodb.json"
TMP = "polar-point/iodb.json.tmp"
GENERIC = {"", "Local Hash Database", "Unknown", "unknown", None}
SIZE_CAP = 95_000_000  # safety: never exceed ~95MB

def keep(e):
    return e.get("family") not in GENERIC

prefix = ('{"db_version":1,"updated_at":"2026-09-13",'
          '"source":"Polar Region v14 (full 1,610,800) filtered to named-family threats",'
          '"note":"Filtered from Polar Region v14: kept named-family entries (real malware families plus known blocklists), excluded 1,536,029 generic Local Hash Database raw hashes. Target under 100MB.",'
          '"entries":[')

kept = 0
fam = collections.Counter()
size = len(prefix.encode("utf-8"))
first = True
with open(SRC, "rb") as f, open(TMP, "wb") as g:
    g.write(prefix.encode("utf-8"))
    for e in ijson.items(f, "entries.item"):
        if not keep(e):
            continue
        s = json.dumps(e, ensure_ascii=False, separators=(",", ":"))
        b = s.encode("utf-8")
        if not first:
            g.write(b",")
            size += 1
        g.write(b)
        size += len(b)
        first = False
        kept += 1
        fam[e.get("family")] += 1
        if size >= SIZE_CAP:
            break
    g.write(b"]}")

os.replace(TMP, DST)
print("KEPT", kept)
print("SIZE_BYTES", os.path.getsize(DST))
print("DISTINCT_FAMILIES", len(fam))
print("TOP_FAMILIES", fam.most_common(8))
