package com.polaris.app.scan;

/**
 * 单条威胁情报记录。
 * 从 {@code IocDatabase} 的内部类抽出来，使 {@link IocBinaryIndex} 等纯逻辑组件
 * 不依赖 Android 运行时，可在 JVM 上直接单元测试。
 */
public class IocEntry {
    public String pkg;
    public String sha256;
    public String md5;        // v2: 32 位 MD5（历史 feed 混入了 MD5，与 sha256 分开存）
    public String sha1;       // v2: 40 位 SHA-1
    public String family;
    public String type;
    public String severity;   // low | medium | high | critical
    public String desc;
    public String[] tags = new String[0];
    public String url;        // 恶意分发 URL（来自 URLhaus 等数据源）
}
