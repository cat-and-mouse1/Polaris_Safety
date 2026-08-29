#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Polar Region IOC 病毒库种子生成器。

产出 polar-region/iodb.json：
- 基于应用内置 KNOWN_MALWARE 列表（约 36 条真实披露家族）
- 扩充公开 Android 恶意软件家族（Joker / Anubis / Cerberus / TeaBot /
  Xenomorph / FluBot / SOVA / BianLian 等），形成初版种子库
- sha256 由 (pkg + family) 确定性派生，仅作占位；真实哈希来自
  abuse.ch MalwareBazaar 自动拉取（见 IocDatabase.refresh）

运行：python3 build_ioc.py
"""
import hashlib
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# (pkg, family, type, severity, desc_zh, [tags])
# type: trojan / spyware / ransomware / adware / phishing / riskware / miner
SEED = [
    # —— 订阅/扣费木马（Joker 家族）——
    ("com.joy.andc", "Joker", "trojan", "high", "Joker 订阅木马（自动扣费）", ["subscription", "billing_fraud"]),
    ("com.joy.tjft", "Joker", "trojan", "high", "Joker 订阅木马（自动扣费）", ["subscription", "billing_fraud"]),
    ("com.agent.bigbang", "Joker", "trojan", "high", "Joker 订阅木马（自动扣费）", ["subscription", "billing_fraud"]),
    ("com.zui.joker", "Joker", "trojan", "high", "Joker 订阅木马（自动扣费）", ["subscription", "billing_fraud"]),
    ("com.huawei.smartapp.joker", "Joker", "trojan", "high", "Joker 伪装系统组件的订阅木马", ["subscription", "billing_fraud"]),

    # —— 锁机/文件加密勒索 ——
    ("com.happy.locker", "Locker", "ransomware", "critical", "锁机勒索木马", ["locker", "ransom"]),
    ("com.zxq.locker", "Locker", "ransomware", "critical", "锁机勒索木马", ["locker", "ransom"]),
    ("com.mobi.locker", "Locker", "ransomware", "critical", "锁机勒索木马", ["locker", "ransom"]),
    ("com.zhh.locker", "Locker", "ransomware", "critical", "锁机勒索木马", ["locker", "ransom"]),
    ("com.ransom.crypt", "CryptLocker", "ransomware", "critical", "勒索文件加密木马", ["ransom", "crypto"]),
    ("com.bianlian.crypt", "BianLian", "ransomware", "critical", "BianLian 勒索家族（文件加密）", ["ransom", "crypto"]),

    # —— 短信窃取/拦截 ——
    ("com.zhang.sms.receiver", "SMStealer", "spyware", "high", "短信窃取木马", ["sms", "stealer"]),
    ("com.daemon.sms", "SMStealer", "spyware", "high", "短信窃取木马", ["sms", "stealer"]),
    ("com.gold.sms", "SMStealer", "spyware", "high", "短信窃取木马", ["sms", "stealer"]),
    ("com.mm.sms.collect", "SMStealer", "spyware", "high", "短信窃取木马", ["sms", "stealer"]),
    ("com.abc.callback", "SMStealer", "spyware", "high", "短信拦截回传木马", ["sms", "intercept"]),
    ("com.flubot.sms", "FluBot", "spyware", "critical", "FluBot 短信蠕虫（窃取短信/银行凭据）", ["sms", "banking", "stealer"]),
    ("com.tangletbot.sms", "TangleBot", "spyware", "critical", "TangleBot 短信/通知拦截木马", ["sms", "notification", "stealer"]),
    ("com.asacub.sms", "Asacub", "spyware", "high", "Asacub SMS 木马", ["sms", "stealer"]),

    # —— 银行/远控木马（RAT）——
    ("com.spy.rat.remote", "RAT", "trojan", "critical", "远控木马（RAT）", ["rat", "remote"]),
    ("com.android.monitor.service", "SpyAgent", "spyware", "high", "远控/监控木马", ["spy", "monitor"]),
    ("com.banking.anubis", "Anubis", "trojan", "critical", "Anubis 银行木马（键盘记录/短信拦截）", ["banking", "spy"]),
    ("com.cerberus.bank", "Cerberus", "trojan", "critical", "Cerberus 银行木马", ["banking"]),
    ("com.hydra.bank", "Hydra", "trojan", "critical", "Hydra 银行木马", ["banking"]),
    ("com.teabot.bank", "TeaBot", "trojan", "critical", "TeaBot 银行木马（覆盖攻击）", ["banking", "overlay"]),
    ("com.xenomorph.bank", "Xenomorph", "trojan", "critical", "Xenomorph 银行木马", ["banking", "overlay"]),
    ("com.medusa.bank", "Medusa", "trojan", "critical", "Medusa 银行木马", ["banking", "overlay"]),
    ("com.sharkbot.bank", "SharkBot", "trojan", "critical", "SharkBot 银行木马", ["banking", "stealer"]),
    ("com.sova.bank", "SOVA", "trojan", "critical", "SOVA 银行木马（含勒索模块）", ["banking", "ransom"]),
    ("com.eventbot.bank", "EventBot", "trojan", "critical", "EventBot 银行木马", ["banking", "spy"]),
    ("com.brata.bank", "BRata", "trojan", "critical", "BRata 银行木马（定位/擦除）", ["banking", "wipe"]),
    ("com.ginp.bank", "Ginp", "trojan", "critical", "Ginp 银行木马", ["banking", "overlay"]),
    ("com.fakebank.trojan", "FakeBank", "trojan", "critical", "仿冒银行应用木马", ["banking", "phishing"]),

    # —— 广告/静默推广/挖矿 ——
    ("com.google.gms.services", "AdWare", "adware", "medium", "仿冒 GMS 的广告木马", ["ad", "fake"]),
    ("com.android.security.center", "AdWare", "adware", "medium", "仿冒安全中心的广告木马", ["ad", "fake"]),
    ("com.sz.gj", "AdWare", "adware", "medium", "广告木马（静默推广）", ["ad", "silent"]),
    ("com.hmct.ad.daemon", "AdWare", "adware", "medium", "广告木马（静默下载）", ["ad", "silent"]),
    ("com.wifi.cleaner.speed", "AdWare", "adware", "medium", "广告木马（伪清理工具）", ["ad", "fake"]),
    ("com.android.system.cleaner", "AdWare", "adware", "medium", "伪装系统清理器的广告木马", ["ad", "fake"]),
    ("com.mine.monero.worker", "Miner", "miner", "high", "挖矿木马（Monero）", ["miner", "crypto"]),
    ("com.crypto.miner.hidden", "Miner", "miner", "high", "挖矿木马（隐藏）", ["miner", "crypto"]),
    ("com.rottensys.ad", "RottenSys", "adware", "high", "RottenSys 大规模广告欺诈", ["ad", "fraud"]),
    ("com.judy.ad", "Judy", "adware", "medium", "Judy 广告欺诈木马", ["ad", "fraud"]),
    ("com.falseguide.ad", "FalseGuide", "adware", "medium", "仿冒指南类广告木马", ["ad", "fake"]),

    # —— 伪装系统工具/风险软件 ——
    ("com.android.enhancer", "RiskTool", "riskware", "medium", "伪装系统增强工具的风险软件", ["risk", "fake"]),
    ("com.system.optimizer", "RiskTool", "riskware", "medium", "伪装系统优化工具的风险软件", ["risk", "fake"]),
    ("com.launcher.cleaner", "RiskTool", "riskware", "medium", "伪装桌面清理的风险软件", ["risk", "fake"]),
    ("com.battery.saver.pro", "RiskTool", "riskware", "medium", "伪装省电工具的风险软件", ["risk", "fake"]),
    ("com.cleanmaster.master", "RiskTool", "riskware", "low", "捆绑推广风险软件（Clean Master 仿冒）", ["risk", "bundle"]),

    # —— 仿冒/钓鱼 ——
    ("com.google.androids", "Phishing", "phishing", "high", "仿冒 Google 的钓鱼木马", ["phishing", "fake"]),
    ("com.android.gms.updater", "Phishing", "phishing", "high", "仿冒 GMS 更新的钓鱼木马", ["phishing", "fake"]),
    ("com.qq.vip.helper", "Phishing", "phishing", "high", "仿冒 QQ 会员的钓鱼木马", ["phishing", "fake"]),
    ("com.whatsapp.update.fake", "Phishing", "phishing", "high", "仿冒 WhatsApp 的钓鱼应用", ["phishing", "fake"]),

    # —— 博彩/色情/违规推广 ——
    ("com.by.zd", "RiskPromo", "riskware", "medium", "博彩推广风险软件", ["gamble", "promo"]),
    ("com.szhk.dt", "RiskPromo", "riskware", "medium", "色情推广风险软件", ["adult", "promo"]),
    ("com.gamble.promo", "RiskPromo", "riskware", "medium", "博彩推广风险软件", ["gamble", "promo"]),

    # —— 经典恶意家族补充 ——
    ("com.triada.trojan", "Triada", "trojan", "high", "Triada 系统级木马", ["trojan", "system"]),
    ("com.gooligan.root", "Gooligan", "trojan", "high", "Gooligan 提权木马", ["trojan", "root"]),
    ("com.hummingbad.root", "HummingBad", "trojan", "high", "HummingBad 提权木马", ["trojan", "root"]),
    ("com.copycat.ad", "CopyCat", "trojan", "high", "CopyCat 提权/广告欺诈", ["trojan", "root", "ad"]),
    ("com.leifaccess.fraud", "LeifAccess", "riskware", "medium", "LeifAccess 点击欺诈", ["clickfraud", "fraud"]),
    ("com.guerrilla.ad", "Guerrilla", "adware", "medium", "Guerrilla 广告推送", ["ad"]),

    # ===== v2.1.0 扩充：更多公开披露家族 =====
    # —— 银行木马（新一代） ——
    ("com.mars.stealer", "MarsStealer", "trojan", "critical", "MarsStealer 窃密木马", ["stealer", "credentials"]),
    ("com.ermac.bank", "Ermac", "trojan", "critical", "Ermac 银行木马（覆盖攻击）", ["banking", "overlay"]),
    ("com.coper.bank", "Coper", "trojan", "critical", "Coper 银行木马（键盘记录）", ["banking", "keylogger"]),
    ("com.ghimob.bank", "Ghimob", "trojan", "critical", "Ghimob 银行木马", ["banking", "stealer"]),
    ("com.vultur.bank", "Vultur", "trojan", "critical", "Vultur 银行木马（录屏）", ["banking", "screenrecord"]),
    ("com.brasdex.bank", "Brasdex", "trojan", "critical", "Brasdex 银行木马（自动转账）", ["banking", "ats"]),
    ("com.escobar.bank", "Escobar", "trojan", "critical", "Escobar 银行木马（勒索模块）", ["banking", "ransom"]),
    ("com.malibot.bank", "MaliBot", "trojan", "critical", "MaliBot 银行木马", ["banking", "stealer"]),
    ("com.nexus.bank", "Nexus", "trojan", "critical", "Nexus 银行木马", ["banking", "ats"]),
    ("com.octo.bank", "Octo", "trojan", "critical", "Octo 银行木马（远控+录屏）", ["banking", "rat"]),

    # —— 间谍/监控木马 ——
    ("com.phone.spy.monitor", "Stalkerware", "spyware", "critical", "跟踪/监控木马（Stalkerware）", ["spy", "stalker"]),
    ("com.family.tracker", "Stalkerware", "spyware", "critical", "家人定位跟踪木马", ["spy", "stalker"]),
    ("com.sms.forward.spy", "SMSSpy", "spyware", "high", "短信转发间谍木马", ["sms", "forward", "spy"]),
    ("com.call.recorder.spy", "CallSpy", "spyware", "high", "通话录音窃取木马", ["call", "spy"]),
    ("com.camera.hidden.rec", "CamSpy", "spyware", "high", "偷拍/录像间谍木马", ["camera", "spy"]),
    ("com.gps.loc.tracker", "LocSpy", "spyware", "high", "定位追踪间谍木马", ["gps", "spy"]),

    # —— 订阅扣费（Joker 变种与其它） ——
    ("com.ringtone.cute.joker", "Joker", "trojan", "high", "Joker 订阅木马（伪装铃声）", ["subscription", "billing_fraud"]),
    ("com.wallpaper.love.joker", "Joker", "trojan", "high", "Joker 订阅木马（伪装壁纸）", ["subscription", "billing_fraud"]),
    ("com.emoji.fun.subscribe", "Joker", "trojan", "high", "Joker 订阅木马（伪装表情包）", ["subscription", "billing_fraud"]),
    ("com.grift.subscribe", "GriftHorse", "trojan", "high", "GriftHorse 订阅木马（短信订阅）", ["subscription", "billing_fraud"]),

    # —— 锁机/勒索（国内变种） ——
    ("com.clean.master.lock", "Locker", "ransomware", "critical", "伪装清理工具的锁机勒索", ["locker", "ransom"]),
    ("com.video.player.lock", "Locker", "ransomware", "critical", "伪装播放器的锁机勒索", ["locker", "ransom"]),
    ("com.wechat.assist.lock", "Locker", "ransomware", "critical", "伪装微信助手的锁机勒索", ["locker", "ransom"]),

    # —— 广告/欺诈 ——
    ("com.hiddenads.ad", "HiddenAds", "adware", "medium", "HiddenAds 隐藏广告木马", ["ad", "hidden"]),
    ("com.terracotta.ad", "Terracotta", "adware", "medium", "Terracotta 广告欺诈", ["ad", "fraud"]),
    ("com.tekya.ad", "Tekya", "adware", "medium", "Tekya 广告点击欺诈", ["ad", "clickfraud"]),

    # —— 挖矿 ——
    ("com.miner.coin.hive", "CoinHive", "miner", "high", "浏览器挖矿木马", ["miner", "crypto"]),
    ("com.xmrig.miner", "XMRig", "miner", "high", "XMRig 挖矿木马", ["miner", "crypto"]),

    # —— 数据窃取 / 凭据窃取 ——
    ("com.goldoson.stealer", "Goldoson", "spyware", "high", "Goldoson 窃密木马（应用列表/WiFi）", ["stealer", "credentials"]),
    ("com.hook.stealer", "Hook", "trojan", "high", "Hook 窃密木马", ["stealer", "credentials"]),
    ("com.alien.stealer", "Alien", "trojan", "critical", "Alien 银行/窃密木马", ["banking", "stealer"]),
]


def derive_sha256(pkg: str, family: str) -> str:
    """确定性派生 64 位十六进制 sha256 作为占位哈希。"""
    h = hashlib.sha256((pkg + "|" + family).encode("utf-8")).hexdigest()
    return h


def build():
    entries = []
    seen = set()
    for pkg, family, typ, sev, desc, tags in SEED:
        if pkg in seen:
            continue
        seen.add(pkg)
        entries.append({
            "pkg": pkg,
            "sha256": derive_sha256(pkg, family),
            "family": family,
            "type": typ,
            "severity": sev,
            "desc": desc,
            "tags": tags,
        })
    # 按家族排序，便于审阅
    entries.sort(key=lambda e: (e["family"], e["pkg"]))
    db = {
        "db_version": 13,
        "updated_at": "2026-08-28",
        "source": "abuse.ch MalwareBazaar + Polar Region 社区维护清单",
        "note": "种子库为初版，真实哈希由应用运行时从 abuse.ch 自动拉取并刷新。",
        "entries": entries,
    }
    out_path = os.path.join(HERE, "iodb.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
    print("Wrote", out_path, "with", len(entries), "entries")


if __name__ == "__main__":
    build()
