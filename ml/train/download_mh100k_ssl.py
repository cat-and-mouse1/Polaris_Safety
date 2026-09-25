#!/usr/bin/env python3
"""
Download MH-100K dataset using HuggingFace Hub with SSL bypass.
"""

import os
import ssl
import httpx
from huggingface_hub import hf_hub_download
from pathlib import Path

# Bypass SSL verification
os.environ['CURL_CA_BUNDLE'] = ''
os.environ['REQUESTS_CA_BUNDLE'] = ''

# Create unverified SSL context
ssl._create_default_https_context = ssl._create_unverified_context

# Output directory
output_dir = Path(__file__).parent.parent / "data" / "raw"
output_dir.mkdir(parents=True, exist_ok=True)

print("=" * 60)
print("Downloading MH-100K Dataset via HuggingFace Hub")
print("=" * 60)

# Repository
repo_id = "arielnlee/MalHunt-100k"
repo_type = "dataset"

# Files to download
files = [
    "mh100-labels.csv",
    "mh100.parquet",
    "feature_names.csv"
]

for filename in files:
    print(f"\n[DOWNLOAD] {filename}...")
    
    try:
        file_path = hf_hub_download(
            repo_id=repo_id,
            filename=filename,
            repo_type=repo_type,
            local_dir=str(output_dir),
            local_dir_use_symlinks=False
        )
        
        print(f"  [OK] Downloaded to: {file_path}")
        
    except Exception as e:
        print(f"  [ERROR] Failed to download {filename}: {e}")
        continue

print("\n" + "=" * 60)
print("Download Complete!")
print("=" * 60)

# List downloaded files
print("\nDownloaded files:")
for f in output_dir.glob("*"):
    if f.is_file():
        print(f"  {f.name}: {f.stat().st_size / 1024 / 1024:.1f} MB")
