#!/usr/bin/env python3
"""构建完整 IOC 数据库 (本地文件聚合 -> iodb.json)."""
import os, json, re
BASE = os.path.dirname(os.path.abspath(__file__))
IP_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")

def load_local_hashes(filepath):
    hashes = []
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                s = line.strip().lower()
                if not s or s.startswith("#"):
                    continue
                s = s.split()[0]
                if re.fullmatch(r"[0-9a-f]{32}|[0-9a-f]{40}|[0-9a-f]{64}", s):
                    hashes.append(s)
    except Exception as e:
        print(f"  Failed to load {filepath}: {e}")
    return hashes

def load_local_packages(filepath):
    packages = []
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            data = json.load(f)
        if isinstance(data, dict):
            packages = list(data.keys())
        elif isinstance(data, list):
            for item in data:
                if isinstance(item, str):
                    packages.append(item)
                elif isinstance(item, dict):
                    if "family" in item and "name" not in item:
                        continue  # malware样本格式, 跳过
                    name = item.get("name") or item.get("path") or ""
                    name = os.path.splitext(os.path.basename(name))[0]
                    if name and not name.startswith("."):
                        packages.append(name)
    except Exception as e:
        print(f"  Failed to load {filepath}: {e}")
    return packages

def load_local_ips(filepath):
    ips = []
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                ip = line.strip().split()[0] if line.strip() else ""
                if ip and not ip.startswith("#") and IP_RE.match(ip):
                    ips.append(ip)
    except Exception as e:
        print(f"  Failed to load {filepath}: {e}")
    return ips

def build():
    entries, seen_hashes, seen_pkgs = [], set(), set()
    def P(p): return os.path.join(BASE, p)
    # 14) 本地 SHA-256/MD5 哈希
    print("\n[14/17] Loading local hashes...")
    for hf in ["data/hashes/romainmarcoux_sha256.txt", "data/hashes/amitambekar_sha256_aa.txt",
               "data/hashes/amitambekar_sha256_ab.txt", "data/hashes/balakdesi_sha256.txt"]:
        fp = P(hf)
        if os.path.exists(fp):
            hs = load_local_hashes(fp)
            print(f"  {hf}: {len(hs)}")
            for h in hs:
                if h not in seen_hashes:
                    seen_hashes.add(h)
                    entries.append({"pkg": "", "sha256": h, "family": "Local Hash Database",
                        "type": "trojan", "severity": "high",
                        "desc": "Malicious hash from local database",
                        "tags": ["local", "aggregated"], "url": ""})
    # 15) 本地恶意包
    print("\n[15/17] Loading local malicious packages...")
    for pf in ["data/packages/datadog_pypi.json", "data/packages/datadog_npm.json",
               "data/packages/pypi_malregistry.json"]:
        fp = P(pf)
        if os.path.exists(fp):
            pkgs = load_local_packages(fp)
            print(f"  {pf}: {len(pkgs)}")
            for pkg in pkgs:
                k = pkg.lower().strip()
                if k and k not in seen_pkgs:
                    seen_pkgs.add(k)
                    entries.append({"pkg": k, "sha256": "", "family": "Local Package Database",
                        "type": "malware", "severity": "high",
                        "desc": "Malicious package from local database",
                        "tags": ["local", "supply-chain"], "url": ""})
    # 16) 本地 IP
    print("\n[16/17] Loading local IP threat intel...")
    for ipf, src in [("data/ips/feodo_tracker.txt", "Feodo Tracker"),
                     ("data/ips/emerging_threats.txt", "Emerging Threats"),
                     ("data/ips/blocklist_de.txt", "Blocklist.de")]:
        fp = P(ipf)
        if os.path.exists(fp):
            ips = load_local_ips(fp)
            print(f"  {ipf}: {len(ips)}")
            for ip in ips:
                if ip not in seen_pkgs:
                    seen_pkgs.add(ip)
                    entries.append({"pkg": ip, "sha256": "", "family": src,
                        "type": "malicious-ip", "severity": "medium",
                        "desc": f"Malicious IP from {src}: {ip}",
                        "tags": ["malicious-ip", src.lower()], "url": ""})
    # 17) 本地恶意软件样本 (兼容 family格式 与 GitHub API格式)
    print("\n[17/17] Loading local malware samples...")
    for mf in ["data/malware/kasuncsb.json", "data/malware/endermanch.json"]:
        fp = P(mf)
        if not os.path.exists(fp):
            continue
        try:
            data = json.load(open(fp, encoding="utf-8", errors="ignore"))
            items = data if isinstance(data, list) else []
            print(f"  {mf}: {len(items)}")
            for it in items:
                if not isinstance(it, dict):
                    continue
                fam = it.get("family") or os.path.splitext(it.get("name", ""))[0]
                if not fam or fam.startswith("."):
                    continue
                key = fam.lower().replace(" ", "-")
                if key not in seen_pkgs:
                    seen_pkgs.add(key)
                    entries.append({"pkg": key, "sha256": "", "family": fam,
                        "type": "malware", "severity": "high",
                        "desc": "Malware family from local database",
                        "tags": ["local", "malware"], "url": ""})
        except Exception as e:
            print(f"  Failed to load {mf}: {e}")
    out = {"count": len(entries), "entries": entries}
    with open(P("iodb.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    print(f"\nDone: {len(entries)} entries -> iodb.json "
          f"(hashes={len(seen_hashes)}, pkgs+ips+families={len(seen_pkgs)})")

if __name__ == "__main__":
    build()
