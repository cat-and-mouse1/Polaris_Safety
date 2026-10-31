#!/usr/bin/env python3
"""
Polaris 病毒库签名工具（ECDSA P-256 + SHA256，与客户端 SHA256withECDSA 对应）。

用法
----
    tools/ioc_sign.py gen                        # 首次：生成密钥对（tools/keys/，不入仓库）
    tools/ioc_sign.py sign <file> [file...]      # 签名，输出 <file>.sig（DER，hex 存储）
    tools/ioc_sign.py verify <file>              # 本地自验（发布前 sanity check）

发布流程（每次更新 iodb.bin / iodb-delta-*.bin 后）
----------------------------------------------------
    tools/ioc_sign.py sign polar-region/iodb.bin polar-point/iodb.bin
    → 与 bin 一起提交/上传，客户端按 <分发地址>.sig 拉取验签，缺 .sig 或验签失败即拒绝。

私钥 tools/keys/ioc_signing_key.pem 已加入 .gitignore —— 只存在于发布机，
丢失后无法再签出新版本（客户端只内置公钥，不存在泄露面）。
"""

import base64
import os
import subprocess
import sys

KEYS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'keys')
KEY_PEM = os.path.join(KEYS_DIR, 'ioc_signing_key.pem')
PUB_B64 = os.path.join(KEYS_DIR, 'ioc_signing_pub.b64')


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stderr, file=sys.stderr)
        sys.exit(1)
    return r.stdout


def gen():
    os.makedirs(KEYS_DIR, exist_ok=True)
    if os.path.exists(KEY_PEM):
        print(f'密钥已存在: {KEY_PEM}（不覆盖）', file=sys.stderr)
        sys.exit(2)
    run(['openssl', 'ecparam', '-name', 'prime256v1', '-genkey', '-noout', '-out', KEY_PEM])
    pub_der = run(['openssl', 'ec', '-in', KEY_PEM, '-pubout', '-outform', 'DER'], )
    # ec -pubout 输出到 stdout 的 DER 是二进制 —— 改用文件管道
    r = subprocess.run(['openssl', 'ec', '-in', KEY_PEM, '-pubout', '-outform', 'DER'],
                       capture_output=True)
    b64 = base64.b64encode(r.stdout).decode()
    open(PUB_B64, 'w').write(b64)
    print(f'密钥对已生成: {KEY_PEM}')
    print(f'公钥(SPKI DER b64): {b64[:48]}...  → 硬编码到 IocDatabase.SIGNING_PUBKEY_B64')


def sig_path(f):
    return f + '.sig'


def sign(files):
    for f in files:
        out = sig_path(f)
        # DER 编码的 ECDSA 签名；以 hex 文本存储（避免任何传输层编码问题）
        r = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', KEY_PEM, f],
                           capture_output=True)
        if r.returncode != 0:
            print(r.stderr.decode(errors='replace'), file=sys.stderr)
            sys.exit(1)
        open(out, 'w').write(r.stdout.hex())
        print(f'{f} → {out}  ({os.path.getsize(out)} B, DER {len(r.stdout)} B)')


def verify(f):
    hex_sig = open(sig_path(f)).read().strip()
    sig = bytes.fromhex(hex_sig)
    r = subprocess.run(['openssl', 'dgst', '-sha256', '-verify',
                        os.path.join(KEYS_DIR, 'ioc_signing_pub.pem'), '-signature', '-'],
                       input=sig, capture_output=True)
    # -signature '-' 不支持，改为临时文件
    tmp = sig_path(f) + '.tmp.der'
    open(tmp, 'wb').write(sig)
    r = subprocess.run(['openssl', 'dgst', '-sha256', '-verify',
                        pub_pem(), '-signature', tmp, f], capture_output=True)
    os.remove(tmp)
    ok = r.returncode == 0
    print(f'{f}: {"VERIFY OK" if ok else "VERIFY FAILED"}  {r.stdout.decode(errors="replace").strip()}')
    return ok


def pub_pem():
    """从 SPKI DER b64 还原 PEM 公钥（供 openssl 自验）。"""
    b64 = open(PUB_B64).read().strip()
    der = base64.b64decode(b64)
    pem = ('-----BEGIN PUBLIC KEY-----\n'
           + '\n'.join(base64.encodebytes(der).decode().splitlines())
           + '\n-----END PUBLIC KEY-----\n')
    pemf = os.path.join(KEYS_DIR, 'ioc_signing_pub.pem')
    open(pemf, 'w').write(pem)
    return pemf


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == 'gen':
        gen()
    elif cmd == 'sign':
        if not os.path.exists(KEY_PEM):
            print('私钥不存在，先运行 gen', file=sys.stderr)
            sys.exit(1)
        sign(sys.argv[2:])
    elif cmd == 'verify':
        ok = all(verify(f) for f in sys.argv[2:])
        sys.exit(0 if ok else 1)
    else:
        print(__doc__)
        sys.exit(1)
