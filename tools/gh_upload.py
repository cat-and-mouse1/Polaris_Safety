#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通过 GitHub REST Contents API 上传单个文件（不依赖 github.com:443 / git push）。

适用场景：本机只能访问 api.github.com，无法走 git 协议或 LFS 上传。

用法：
    GH_TOKEN=ghp_xxx python tools/gh_upload.py <本地文件> <仓库内路径> [owner/repo] [branch]

示例：
    GH_TOKEN=... python tools/gh_upload.py polar-point/iodb.bin polar-point/iodb.bin
    GH_TOKEN=... python tools/gh_upload.py polar-region/iodb.bin polar-region/iodb.bin

注意：
- 文件 >50MB 时 GitHub 可能拒绝或需要分批；本脚本会先探测远端 sha 再 PUT。
- 通过 API 上传的是普通 blob（不走 LFS），raw 域名可直接取到二进制，
  对客户端下载反而更简单（LFS 文件必须用 media 域名）。
"""
import base64
import json
import os
import ssl
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def opener():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),               # 绕过系统代理，直连 api.github.com
        urllib.request.HTTPSHandler(context=ctx))


def request(op, method, url, token, data=None):
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "polaris-ioc-uploader",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    body = None
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with op.open(req, timeout=180) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    local, remote_path = sys.argv[1], sys.argv[2]
    repo = sys.argv[3] if len(sys.argv) > 3 else "cat-and-mouse1/Polaris_Safety"
    branch = sys.argv[4] if len(sys.argv) > 4 else "main"
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        print("缺少 GH_TOKEN 环境变量")
        return 2

    size = os.path.getsize(local)
    print(f"上传 {local} -> {repo}/{remote_path} ({size / 1024 / 1024:.1f} MiB)")
    op = opener()
    contents_url = f"{API}/repos/{repo}/contents/{remote_path}"

    # 已存在则要带 sha 才能更新
    sha = None
    req = urllib.request.Request(
        f"{contents_url}?ref={branch}",
        headers={"Authorization": "Bearer " + token,
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "polaris-ioc-uploader"})
    try:
        with op.open(req, timeout=60) as resp:
            info = json.loads(resp.read())
            sha = info.get("sha")
            print(f"  远端已存在 sha={sha} size={info.get('size')}")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print("  远端不存在，将新建")
        else:
            print(f"  探测远端失败 HTTP {e.code}: {e.read()[:200]}")
            return 1

    with open(local, "rb") as f:
        content = base64.b64encode(f.read()).decode("ascii")
    payload = {
        "message": f"chore(ioc): upload {remote_path} ({size} bytes, v2 binary index)",
        "content": content,
        "branch": branch,
    }
    if sha:
        payload["sha"] = sha

    code, resp = request(op, "PUT", contents_url, token, payload)
    if code in (200, 201):
        commit = (resp.get("commit") or {}).get("sha", "")
        print(f"  OK HTTP {code} commit={commit[:12]}")
        print(f"  raw: https://raw.githubusercontent.com/{repo}/{branch}/{remote_path}")
        return 0
    print(f"  失败 HTTP {code}: {str(resp)[:400]}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
