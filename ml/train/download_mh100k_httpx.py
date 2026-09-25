#!/usr/bin/env python3
"""
Download MH-100K dataset using httpx with SSL bypass.
"""

import httpx
import ssl
from pathlib import Path

# Output directory
output_dir = Path(__file__).parent.parent / "data" / "raw"
output_dir.mkdir(parents=True, exist_ok=True)

print("=" * 60)
print("Downloading MH-100K Dataset")
print("=" * 60)

# Base URL
base_url = "https://huggingface.co/datasets/arielnlee/MalHunt-100k/resolve/main"

# Files to download
files = [
    "mh100-labels.csv",
    "mh100.parquet",
    "feature_names.csv"
]

# Create client with SSL verification disabled
client = httpx.Client(verify=False)

for filename in files:
    print(f"\n[DOWNLOAD] {filename}...")
    url = f"{base_url}/{filename}"
    output_file = output_dir / filename
    
    try:
        response = client.get(url, follow_redirects=True)
        response.raise_for_status()
        
        with open(output_file, 'wb') as f:
            f.write(response.content)
        
        print(f"  [OK] Downloaded {len(response.content) / 1024 / 1024:.1f} MB")
        
    except Exception as e:
        print(f"  [ERROR] Failed to download {filename}: {e}")
        continue

client.close()

print("\n" + "=" * 60)
print("Download Complete!")
print("=" * 60)

# List downloaded files
print("\nDownloaded files:")
for f in output_dir.glob("*"):
    if f.is_file():
        print(f"  {f.name}: {f.stat().st_size / 1024 / 1024:.1f} MB")
