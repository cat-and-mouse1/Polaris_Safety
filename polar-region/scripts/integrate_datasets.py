#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Android 恶意样本数据集整合脚本
功能：下载 MH-100K/AndroTruth 数据集并转换为 Polar Region IOC 格式
注意：部分需要自行下载数据集文件后放入 scripts/ 目录
"""

import json, csv, os, sys, hashlib
from datetime import datetime, timezone

# ============= 配置区 ==============
IODB_PATH = "iodb.json"
SCRIPTS_DIR = "scripts/"
# ===================================

def parse_mh_csv(filepath):
    """解析 MH CSV：列包括 sha256, package_name, malware_type"""
    entries = []
    try:
        with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
            reader = csv.DictReader(f)
            for row in reader:
                sha = row.get('sha256', '').strip().lower()
                pkg = row.get('package_name', '').strip().lower()
                malware_type = row.get('malware_type', '').strip()
                if not sha: continue
                severity = "high" if malware_type else "medium"
                entries.append({
                    "pkg": pkg if pkg else "",
                    "sha256": sha,
                    "family": malware_type or "Academic-Malware",
                    "type": "trojan",
                    "severity": severity,
                    "desc": f"Dataset: {malware_type or 'Academic'}",
                    "tags": [malware_type or "academic"] if malware_type else ["academic"]
                })
    except Exception as e:
        print(f"⚠️  读取 CSV 失败: {e}")
    return entries

def parse_androtruth_json(filepath):
    """解析 AndroTruth JSON（假设每条含 sha256, package, malware_type）"""
    entries = []
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)
            for item in data:
                sha = item.get('sha256', '').strip().lower()
                pkg = item.get('package', '').strip().lower()
                mt = item.get('malware_type', '').strip()
                if not sha: continue
                entries.append({
                    "pkg": pkg if pkg else "",
                    "sha256": sha,
                    "family": mt or "AndroTruth-Family",
                    "type": "trojan",
                    "severity": "high",
                    "desc": f"AndroTruth: {mt}",
                    "tags": [mt] if mt else []
                })
    except Exception as e:
        print(f"⚠️  读取 JSON 失败: {e}")
    return entries

def main():
    print("=== Android 恶意样本数据集整合 ===\n")
    all_new_entries = []
    
    # 1. 处理 MH CSV（如果存在）
    csv_files = [f for f in os.listdir(SCRIPTS_DIR) if f.endswith('.csv')]
    for csv_file in csv_files:
        filepath = os.path.join(SCRIPTS_DIR, csv_file)
        print(f"解析 CSV: {csv_file}")
        entries = parse_mh_csv(filepath)
        print(f"  -> 解析出 {len(entries)} 条记录")
        all_new_entries.extend(entries)
    
    # 2. 处理 AndroTruth JSON（如果存在）
    json_files = [f for f in os.listdir(SCRIPTS_DIR) if f.endswith('.json') and f != 'iodb.json']
    for json_file in json_files:
        filepath = os.path.join(SCRIPTS_DIR, json_file)
        print(f"解析 JSON: {json_file}")
        entries = parse_androtruth_json(filepath)
        print(f"  -> 解析出 {len(entries)} 条记录")
        all_new_entries.extend(entries)
    
    if not all_new_entries:
        print("没有检测到数据集文件，请按说明将文件放入 scripts/ 目录后重新运行。")
        return
    
    # 3. 读取现有 iodb.json
    if os.path.exists(IODB_PATH):
        with open(IODB_PATH, 'r', encoding='utf-8') as f:
            existing = json.load(f)
        existing_hashes = {e['sha256'] for e in existing.get('entries', []) if e.get('sha256')}
        print(f"现有病毒库条目数: {len(existing.get('entries', []))}")
    else:
        existing = {"db_version": 0, "updated_at": "", "source": "datasets", "entries": []}
        existing_hashes = set()
    
    # 4. 增量合并
    added = 0
    for e in all_new_entries:
        if e['sha256'] not in existing_hashes:
            existing.setdefault('entries', []).append(e)
            added += 1
    
    if added > 0:
        existing['db_version'] = existing.get('db_version', 0) + 1
        existing['updated_at'] = datetime.now(timezone.utc).isoformat()
        existing['source'] = f"datasets (+{added} new)+malwarebazaar"
        
        with open(IODB_PATH, 'w', encoding='utf-8') as f:
            json.dump(existing, f, ensure_ascii=False, indent=2)
        print(f"✅ 已写入 iodb.json，新增 {added} 条，版本 v{existing['db_version']}")
        
        # 4. 同步种子库
        import subprocess
        result = subprocess.run(
            ['python', '../polar-region/sync_seed.py'], 
            capture_output=True, text=True, cwd='polar-region'
        )
        print(result.stdout)
        if result.returncode == 0:
            print("✅ 种子库同步成功")
        else:
            print("⚠️ 种子库同步有警告（数据已写入 iodb.json）")
    else:
        print("没有新条目需要添加（可能已全部存在）。")

if __name__ == "__main__":
    main()