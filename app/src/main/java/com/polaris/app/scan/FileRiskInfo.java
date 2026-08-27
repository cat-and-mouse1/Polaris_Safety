package com.polaris.app.scan;

import com.polaris.app.util.AiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个文件（APK / 脚本 / 勒索文件等）的扫描结果。
 * 与 {@link AppRiskInfo}（应用级）对应，用于「扫描中心」的文件夹/全局文件扫描。
 */
public class FileRiskInfo {

    public static final int LEVEL_SAFE = 0;
    public static final int LEVEL_LOW = 1;
    public static final int LEVEL_MEDIUM = 2;
    public static final int LEVEL_HIGH = 3;

    /** 展示用路径（全盘扫描为绝对路径；SAF 扫描为 uri 字符串或文档名）。 */
    public String path;
    /** 文件名。 */
    public String name;
    /** 文件大小（字节）。 */
    public long size;
    /** 风险分数 0..100。 */
    public int score;
    public int level;
    /** 命中特征描述。 */
    public List<String> reasons = new ArrayList<>();
    /** 是否为 APK 安装包。 */
    public boolean isApk;
    /** 来源类型：full（全盘路径）/ saf（存储访问框架文档）。 */
    public String source = "full";

    /** SHA-256（APK/DEX/JAR 等可执行载荷计算，用于开源情报哈希匹配）。 */
    public String sha256;
    /** 命中 Polar Region 开源情报的恶意家族（未命中为 null）。 */
    public String iocFamily;
    /** 命中时的严重度（low/medium/high/critical）。 */
    public String iocSeverity;

    /** AI 判定结论（AI 引擎扫描时填充；机械扫描为 null）。 */
    public AiClient.AiVerdict verdict;

    public FileRiskInfo(String path, String name, long size) {
        this.path = path;
        this.name = name;
        this.size = size;
    }

    public static int levelOf(int score) {
        if (score >= 80) return LEVEL_HIGH;
        if (score >= 45) return LEVEL_MEDIUM;
        if (score >= 20) return LEVEL_LOW;
        return LEVEL_SAFE;
    }
}
