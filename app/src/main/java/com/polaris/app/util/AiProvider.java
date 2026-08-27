package com.polaris.app.util;

import android.content.Context;

import com.polaris.app.R;

/**
 * AI 判定引擎支持的 7 家大模型服务商。
 *
 * 除 Claude 使用 Anthropic 原生 Messages API 外，
 * 其余 6 家均提供 OpenAI Chat Completions 兼容端点，可统一客户端调用。
 * （Hy3 = 腾讯混元 Hy3，2026-07 发布，兼容 OpenAI 协议。）
 */
public enum AiProvider {

    /** 默认推荐：置于 AI 引擎页最上方的大卡片。 */
    DEEPSEEK(
            "deepseek", R.string.ai_provider_deepseek, R.color.ai_brand_deepseek, "DS",
            "https://api.deepseek.com", "deepseek-v4-pro"),
    HY3(
            "hy3", R.string.ai_provider_hy3, R.color.ai_brand_hy3, "混元",
            "https://api.hunyuan.cloud.tencent.com/v1", "hy3"),
    GLM(
            "glm", R.string.ai_provider_glm, R.color.ai_brand_glm, "GLM",
            "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus"),
    KIMI(
            "kimi", R.string.ai_provider_kimi, R.color.ai_brand_kimi, "K",
            "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    CHATGPT(
            "chatgpt", R.string.ai_provider_chatgpt, R.color.ai_brand_chatgpt, "GPT",
            "https://api.openai.com/v1", "gpt-4o-mini"),
    GEMINI(
            "gemini", R.string.ai_provider_gemini, R.color.ai_brand_gemini, "G",
            "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    CLAUDE(
            "claude", R.string.ai_provider_claude, R.color.ai_brand_claude, "C",
            "https://api.anthropic.com", "claude-sonnet-4-6");

    /** 稳定标识，存入 Prefs。 */
    public final String id;
    /** 显示名资源。 */
    public final int nameRes;
    /** 品牌色资源。 */
    public final int brandColorRes;
    /** 圆形徽标上的缩写文字。 */
    public final String badgeText;
    /** API Base URL。 */
    public final String baseUrl;
    /** 默认模型名。 */
    public final String defaultModel;

    AiProvider(String id, int nameRes, int brandColorRes, String badgeText,
               String baseUrl, String defaultModel) {
        this.id = id;
        this.nameRes = nameRes;
        this.brandColorRes = brandColorRes;
        this.badgeText = badgeText;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
    }

    public String displayName(Context context) {
        return context.getString(nameRes);
    }

    /** 是否使用 Anthropic 原生 Messages API。 */
    public boolean isClaude() {
        return this == CLAUDE;
    }

    /** Chat 完成端点 URL。 */
    public String chatUrl() {
        return isClaude() ? baseUrl + "/v1/messages" : baseUrl + "/chat/completions";
    }

    /** 按 id 反查。 */
    public static AiProvider fromId(String id) {
        if (id == null) return null;
        for (AiProvider p : values()) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }
}
