package com.polaris.app.util;

import android.content.Context;

import com.polaris.app.R;
import com.polaris.app.scan.AppRiskInfo;
import com.polaris.app.scan.FileRiskInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 判定客户端：统一封装 7 家服务商的调用协议。
 *
 * - 6 家（Hy3 / GLM / Kimi / ChatGPT / Gemini / DeepSeek）走 OpenAI Chat Completions 格式；
 * - Claude 走 Anthropic 原生 Messages API（x-api-key + anthropic-version）。
 *
 * 判定结果：解析模型输出的 JSON 数组为 {@link AiVerdict} 列表，
 * 每个风险应用得到「清除 / 保留」建议、置信度与中文理由。
 */
public final class AiClient {

    private AiClient() {}

    /** 请求/解析失败时抛出。 */
    public static class AiException extends Exception {
        public AiException(String message) {
            super(message);
        }
    }

    /** 单个应用的 AI 判定结论。 */
    public static class AiVerdict {
        public String packageName;
        public boolean remove;          // true=建议清除, false=建议保留
        public float confidence;        // 0..1
        public String reason = "";
    }

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 90000;
    /** 单次送入 AI 的应用上限（保护 token 预算）。 */
    private static final int MAX_APPS_TO_AI = 40;

    // ---------- 对外接口 ----------

    /**
     * 通用对话调用，返回模型回复文本。可在任意后台线程调用。
     */
    public static String chat(Context context, AiProvider provider, String apiKey,
                              String model, String system, String user) throws AiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AiException("API Key 为空");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(provider.chatUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoInput(true);
            conn.setDoOutput(true);

            String body;
            if (provider.isClaude()) {
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                body = claudeBody(model, system, user);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                body = openAiBody(model, system, user);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String resp = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new AiException("HTTP " + code + ": " + truncate(resp, 160));
            }
            if (provider.isClaude()) return parseClaude(resp);
            return parseOpenAi(resp);
        } catch (IOException e) {
            throw new AiException(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 用 AI 判断扫描报告中的应用「清除 / 保留」。
     * 只把达到低风险及以上的应用送入模型，并限制数量。
     * 返回的列表只包含模型明确评估过的应用；未评估的应用默认视为「保留」。
     */
    public static List<AiVerdict> judge(Context context, AiProvider provider, String apiKey,
                                        String model, List<AppRiskInfo> risks) throws AiException {
        StringBuilder report = new StringBuilder();
        int sent = 0;
        for (AppRiskInfo r : risks) {
            if (r.level < AppRiskInfo.LEVEL_LOW) continue; // 安全应用不送审
            if (sent >= MAX_APPS_TO_AI) break;
            sent++;
            report.append("- ").append(r.packageName)
                    .append(" | 名称:").append(r.appName)
                    .append(" | 分数:").append(r.score)
                    .append(" | 等级:").append(levelName(context, r.level))
                    .append(r.isSystem ? " | 系统应用" : "")
                    .append(" | 特征:").append(joinReasons(r.reasons))
                    .append('\n');
        }
        if (sent == 0) return new ArrayList<>();

        String system = context.getString(R.string.ai_system_prompt);
        String user = context.getString(R.string.ai_user_prompt, report.toString());
        String raw = chat(context, provider, apiKey, model, system, user);
        return parseVerdicts(raw);
    }

    /**
     * 用 AI 判断文件扫描报告中的风险文件「清除(隔离) / 保留」。
     * 复用 {@link AiVerdict}，其中 packageName 字段承载文件标识（路径或 uri）。
     * 只把达到低风险及以上的文件送入模型，并限制数量。
     */
    public static List<AiVerdict> judgeFiles(Context context, AiProvider provider, String apiKey,
                                             String model, List<FileRiskInfo> risks) throws AiException {
        StringBuilder report = new StringBuilder();
        int sent = 0;
        for (FileRiskInfo r : risks) {
            if (r.level < FileRiskInfo.LEVEL_LOW) continue;
            if (sent >= MAX_APPS_TO_AI) break;
            sent++;
            report.append("- ").append(r.path)
                    .append(" | 名称:").append(r.name)
                    .append(" | 大小:").append(humanSize(r.size))
                    .append(" | 分数:").append(r.score)
                    .append(" | 等级:").append(levelName(context, r.level))
                    .append(r.isApk ? " | APK安装包" : "")
                    .append(" | 特征:").append(joinReasons(r.reasons))
                    .append('\n');
        }
        if (sent == 0) return new ArrayList<>();

        String system = context.getString(R.string.ai_system_prompt_files);
        String user = context.getString(R.string.ai_user_prompt, report.toString());
        String raw = chat(context, provider, apiKey, model, system, user);
        return parseVerdicts(raw);
    }

    // ---------- 请求体 ----------

    private static String openAiBody(String model, String system, String user) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", system));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", user));
            body.put("messages", messages);
            body.put("temperature", 0.2);
            body.put("stream", false);
            return body.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String claudeBody(String model, String system, String user) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("system", system);
            body.put("max_tokens", 2048);
            body.put("temperature", 0.2);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", user));
            body.put("messages", messages);
            return body.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 响应解析 ----------

    private static String parseOpenAi(String resp) throws AiException {
        try {
            JSONObject obj = new JSONObject(resp);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new AiException("响应缺少 choices");
            }
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            String content = msg != null ? msg.optString("content") : null;
            if (content == null || content.isEmpty()) {
                throw new AiException("模型返回内容为空");
            }
            return content;
        } catch (JSONException e) {
            throw new AiException("响应解析失败: " + truncate(resp, 120));
        }
    }

    private static String parseClaude(String resp) throws AiException {
        try {
            JSONObject obj = new JSONObject(resp);
            JSONArray content = obj.optJSONArray("content");
            if (content == null || content.length() == 0) {
                throw new AiException("响应缺少 content");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    sb.append(block.optString("text"));
                }
            }
            if (sb.length() == 0) throw new AiException("模型返回内容为空");
            return sb.toString();
        } catch (JSONException e) {
            throw new AiException("响应解析失败: " + truncate(resp, 120));
        }
    }

    /** 从模型输出中容错提取 JSON 数组并解析为判定列表。 */
    private static List<AiVerdict> parseVerdicts(String raw) throws AiException {
        String json = extractJsonArray(raw);
        if (json == null) {
            throw new AiException("模型未返回有效 JSON 数组");
        }
        List<AiVerdict> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                AiVerdict v = new AiVerdict();
                v.packageName = o.optString("package");
                String action = o.optString("action").toLowerCase();
                v.remove = "remove".equals(action) || "清除".equals(action);
                v.confidence = (float) o.optDouble("confidence", 0.5);
                v.reason = o.optString("reason");
                if (v.packageName == null || v.packageName.isEmpty()) continue;
                out.add(v);
            }
        } catch (JSONException e) {
            throw new AiException("判定结果解析失败");
        }
        if (out.isEmpty()) throw new AiException("模型未给出任何判定");
        return out;
    }

    /** 提取从第一个 '[' 到最后一个 ']' 之间的内容，剥离 ```json 代码块。 */
    private static String extractJsonArray(String raw) {
        if (raw == null) return null;
        String s = raw.replace("```json", "").replace("```", "").trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    // ---------- 小工具 ----------

    private static String levelName(Context context, int level) {
        switch (level) {
            case AppRiskInfo.LEVEL_HIGH: return context.getString(R.string.risk_high);
            case AppRiskInfo.LEVEL_MEDIUM: return context.getString(R.string.risk_medium);
            case AppRiskInfo.LEVEL_LOW: return context.getString(R.string.risk_low);
            default: return context.getString(R.string.risk_safe);
        }
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return "无明显特征";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String r : reasons) {
            if (n++ >= 4) { sb.append("…"); break; }
            if (sb.length() > 0) sb.append("; ");
            sb.append(r);
        }
        return sb.toString();
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String humanSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", size / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    /** 便捷工具：按包名建立判定索引（供结果页查询）。 */
    public static Map<String, AiVerdict> indexOf(List<AiVerdict> verdicts) {
        Map<String, AiVerdict> map = new HashMap<>();
        if (verdicts != null) {
            for (AiVerdict v : verdicts) {
                if (v.packageName != null) map.put(v.packageName, v);
            }
        }
        return map;
    }
}
