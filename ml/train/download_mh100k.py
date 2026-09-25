#!/usr/bin/env python3
"""
Download MH-100K dataset from HuggingFace.
"""

import ssl
import urllib.request
import os
from pathlib import Path

# Bypass SSL verification
ssl._create_default_https_context = ssl._create_unverified_context

# URLs
URLS = {
    "labels": "https://huggingface.co/datasets/arielnlee/MalHunt-100k/resolve/main/mh100-labels.csv",
    "features": "https://huggingface.co/datasets/arielnlee/MalHunt-100k/resolve/main/mh100.parquet",
    "feature_names": "https://huggingface.co/datasets/arielnlee/MalHunt-100k/resolve/main/feature_names.csv"
}

# Output directory
output_dir = Path(__file__).parent.parent / "data" / "raw"
output_dir.mkdir(parents=True, exist_ok=True)

print("=" * 60)
print("Downloading MH-100K Dataset")
print("=" * 60)

for name, url in URLS.items():
    output_file = output_dir / url.split("/")[-1]
    
    if output_file.exists():
        print(f"[SKIP] {name} already exists: {output_file}")
        continue
    
    print(f"\n[DOWNLOAD] {name}...")
    print(f"  URL: {url}")
    print(f"  Output: {output_file}")
    
    try:
        # Download with progress
        def progress(count, block_size, total_size):
            if total_size > 0:
                percent = int(count * block_size * 100 / total_size)
                print(f"\r  Progress: {percent}%", end="", flush=True)
        
        urllib.request.urlretrieve(url, str(output_file), reporthook=progress)
        print(f"\n  [OK] Downloaded {output_file.stat().st_size / 1024 / 1024:.1f} MB")
        
    except Exception as e:
        print(f"\n  [ERROR] Failed to download {name}: {e}")
        continue

print("\n" + "=" * 60)
print("Download Complete!")
print("=" * 60)

# List downloaded files
print("\nDownloaded files:")
for f in output_dir.glob("*"):
    print(f"  {f.name}: {f.stat().st_size / 1024 / 1024:.1f} MB")
