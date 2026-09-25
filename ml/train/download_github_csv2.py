#!/usr/bin/env python3
"""
Download MH-100K dataset from GitHub - zip-csv directory.
"""

import ssl
import urllib.request
import json
from pathlib import Path

# Bypass SSL
ssl._create_default_https_context = ssl._create_unverified_context

# Output directory
output_dir = Path(__file__).parent.parent / "data" / "raw"
output_dir.mkdir(parents=True, exist_ok=True)

print("=" * 60)
print("Downloading MH-100K from GitHub (zip-csv)")
print("=" * 60)

# GitHub API to list files in zip-csv directory
url = "https://api.github.com/repos/Malware-Hunter/MH-100K-dataset/contents/data/zip-csv"

try:
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    response = urllib.request.urlopen(req, timeout=30)
    data = json.loads(response.read())
    
    print("\nFiles in zip-csv:")
    for item in data:
        name = item.get("name", "unknown")
        size = item.get("size", 0)
        download_url = item.get("download_url", "")
        
        print(f"  {name}: {size / 1024 / 1024:.1f} MB")
        
        if download_url:
            print(f"  Downloading {name}...")
            try:
                req = urllib.request.Request(download_url, headers={"User-Agent": "Mozilla/5.0"})
                response = urllib.request.urlopen(req, timeout=120)
                
                # Read response properly
                content = response.read()
                
                output_file = output_dir / name
                with open(output_file, "wb") as f:
                    f.write(content)
                
                print(f"  [OK] Saved to {output_file}")
                
            except Exception as e:
                print(f"  [ERROR] Failed to download {name}: {e}")
        
except Exception as e:
    print(f"Error: {e}")

print("\n" + "=" * 60)
print("Done!")
print("=" * 60)
