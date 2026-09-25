package com.polaris.app.scan;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.polaris.app.util.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On-device ML malware detection engine v3.
 * Supports dual-model: MH-100K (Logistic Regression) and MH-1M (XGBoost via pure Java).
 * Features: 38 features (15 permission combos + 3 context + 20 DEX API patterns).
 * User can select model in settings.
 */
public class MlScanner {
    private static final String TAG = "MlScanner";

    // MH-100K Logistic Regression model files
    private static final String LR_MODEL_FILE = "malware_detector_v3.json";
    private static final String LR_METADATA_FILE = "malware_detector_v3_metadata.json";

    // MH-1M XGBoost model files
    private static final String XGB_MODEL_FILE = "model_mh1m.json";
    private static final String XGB_SCALER_FILE = "scaler_mh1m.json";
    private static final String XGB_FEATURES_FILE = "features_mh1m.json";
    private static final String XGB_METADATA_FILE = "metadata_mh1m.json";

    // Model type constants
    public static final String MODEL_MH100K = "mh100k";
    public static final String MODEL_MH1M = "mh1m";

    private Context context;
    private Prefs prefs;

    // Current model type
    private String currentModelType = MODEL_MH100K;

    // LR Model (MH-100K)
    private JSONObject lrModelData;
    private JSONObject lrMetadata;
    private float[][] lrCoef;
    private float[] lrIntercept;
    private float[] lrScalerMean;
    private float[] lrScalerScale;
    private float lrThreshold = 0.5f;

    // XGB Model (MH-1M) - pure Java tree structures
    private XGBTree[] xgbTrees;
    private float[] xgbScalerMean;
    private float[] xgbScalerScale;
    private float xgbThreshold = 0.8464f;

    // Common
    private float[] scalerMean;
    private float[] scalerScale;
    private float threshold;
    private boolean isInitialized = false;

    // ========================================================================
    // Permission Combination Patterns (15 features)
    // ========================================================================
    // 必须为有序 Map：extractFeatures 的 features[0-14] 顺序 == 训练顺序(FEATURE_NAMES)，否则静默错位。
    private static final Map<String, String[]> PERMISSION_COMBOS = new LinkedHashMap<>();

    static {
        PERMISSION_COMBOS.put("SMS_MALWARE", new String[]{
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.RECEIVE_WAP_PUSH"
        });
        PERMISSION_COMBOS.put("PHONE_MALWARE", new String[]{
            "android.permission.READ_PHONE_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG"
        });
        PERMISSION_COMBOS.put("CONTACTS_MALWARE", new String[]{
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.GET_ACCOUNTS"
        });
        PERMISSION_COMBOS.put("LOCATION_MALWARE", new String[]{
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        });
        PERMISSION_COMBOS.put("CAMERA_MALWARE", new String[]{
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO"
        });
        PERMISSION_COMBOS.put("STORAGE_MALWARE", new String[]{
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE"
        });
        PERMISSION_COMBOS.put("ADMIN_MALWARE", new String[]{
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.INSTALL_PACKAGES",
            "android.permission.DELETE_PACKAGES",
            "android.permission.BIND_ACCESSIBILITY_SERVICE"
        });
        PERMISSION_COMBOS.put("OVERLAY_MALWARE", new String[]{
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.BIND_ACCESSIBILITY_SERVICE"
        });
        PERMISSION_COMBOS.put("CLIPBOARD_MALWARE", new String[]{
            "android.permission.READ_CLIPBOARD",
            "android.permission.WRITE_CLIPBOARD"
        });
        PERMISSION_COMBOS.put("CALENDAR_MALWARE", new String[]{
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR"
        });
        PERMISSION_COMBOS.put("SENSOR_MALWARE", new String[]{
            "android.permission.BODY_SENSORS",
            "android.permission.ACTIVITY_RECOGNITION"
        });
        PERMISSION_COMBOS.put("BLUETOOTH_MALWARE", new String[]{
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT"
        });
        PERMISSION_COMBOS.put("WIFI_MALWARE", new String[]{
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE"
        });
        PERMISSION_COMBOS.put("NFC_MALWARE", new String[]{
            "android.permission.NFC"
        });
        PERMISSION_COMBOS.put("NOTIFICATION_MALWARE", new String[]{
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED"
        });
    }

    // ========================================================================
    // Feature Names (38 features total, v3 pipeline)
    // ========================================================================
    private static final String[] FEATURE_NAMES = {
        // Permission combos (0-14)
        "combo_SMS_MALWARE", "combo_PHONE_MALWARE", "combo_CONTACTS_MALWARE",
        "combo_LOCATION_MALWARE", "combo_CAMERA_MALWARE", "combo_STORAGE_MALWARE",
        "combo_ADMIN_MALWARE", "combo_OVERLAY_MALWARE", "combo_CLIPBOARD_MALWARE",
        "combo_CALENDAR_MALWARE", "combo_SENSOR_MALWARE", "combo_BLUETOOTH_MALWARE",
        "combo_WIFI_MALWARE", "combo_NFC_MALWARE", "combo_NOTIFICATION_MALWARE",
        // Context (15-17)
        "ctx_permission_count", "ctx_dangerous_perm_count", "ctx_has_internet_permission",
        // API patterns (18-37)
        "api_reflection", "api_dynamic_load", "api_crypto", "api_network", "api_sms",
        "api_phone", "api_contacts", "api_location", "api_camera", "api_audio",
        "api_file", "api_shared_prefs", "api_intent", "api_service", "api_broadcast",
        "api_content_provider", "api_notification", "api_accessibility", "api_device_admin", "api_package"
    };

    /**
     * Pure Java XGBoost tree node structure.
     */
    private static class XGBTree {
        int leftChild;
        int rightChild;
        int featureIdx;
        float splitCondition;
        float leafValue;
        boolean isLeaf;

        float predict(float[] features, XGBTree[] trees) {
            if (isLeaf) {
                return leafValue;
            }
            if (features[featureIdx] < splitCondition) {
                return trees[leftChild].predict(features, trees);
            } else {
                return trees[rightChild].predict(features, trees);
            }
        }
    }

    public MlScanner(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new Prefs(context);
    }

    /**
     * Initialize ML scanner based on user preference.
     * Loads either MH-100K LR model or MH-1M XGBoost model.
     */
    public boolean init() {
        if (isInitialized) return true;

        // Get user preference for model type
        String modelType = new Prefs(context).getMlModel();
        Log.i(TAG, "Initializing ML Scanner with model: " + modelType);

        try {
            if (MODEL_MH1M.equals(modelType)) {
                return initXGBoostModel();
            } else {
                return initLRModel();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ML Scanner", e);
            // Fallback to LR model
            try {
                return initLRModel();
            } catch (Exception e2) {
                Log.e(TAG, "Failed to initialize fallback LR model", e2);
                return false;
            }
        }
    }

    /**
     * Initialize MH-100K Logistic Regression model (legacy).
     */
    private boolean initLRModel() {
        try {
            // Load metadata
            String metaJson = loadAsset(context, LR_METADATA_FILE);
            JSONObject metadata = new JSONObject(metaJson);
            threshold = (float) metadata.getDouble("threshold");

            // Load JSON model
            String modelJson = loadAsset(context, LR_MODEL_FILE);
            JSONObject modelData = new JSONObject(modelJson);
            String modelType = modelData.getString("type");

            // Parse model weights
            if ("logistic_regression".equals(modelType)) {
                parseLRModel(new JSONObject(loadAsset(context, LR_MODEL_FILE)));
            } else {
                Log.e(TAG, "Unexpected LR model type: " + modelType);
                return false;
            }

            currentModelType = MODEL_MH100K;
            isInitialized = true;
            Log.i(TAG, "LR Model (MH-100K) initialized, threshold=" + threshold);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize LR Model (MH-100K)", e);
            return false;
        }
    }

    /**
     * Initialize MH-1M XGBoost model - pure Java tree inference.
     */
    private boolean initXGBoostModel() {
        try {
            // Load metadata
            String metaJson = loadAsset(context, XGB_METADATA_FILE);
            JSONObject metadata = new JSONObject(metaJson);
            xgbThreshold = (float) metadata.getDouble("threshold");
            threshold = xgbThreshold;

            // Load scaler
            String scalerJson = loadAsset(context, XGB_SCALER_FILE);
            JSONObject scalerData = new JSONObject(scalerJson);
            JSONArray meanArray = scalerData.getJSONArray("mean");
            // 兼容键名: scale / std
            JSONArray scaleArray = scalerData.has("scale")
                    ? scalerData.getJSONArray("scale")
                    : scalerData.getJSONArray("std");
            scalerMean = new float[meanArray.length()];
            scalerScale = new float[scaleArray.length()];
            for (int i = 0; i < meanArray.length(); i++) {
                scalerMean[i] = (float) meanArray.getDouble(i);
                scalerScale[i] = (float) scaleArray.getDouble(i);
            }

            // Load XGBoost model JSON and parse trees
            String modelJson = loadAsset(context, XGB_MODEL_FILE);
            JSONObject modelData = new JSONObject(modelJson);
            parseXGBoostModel(modelData);

            currentModelType = MODEL_MH1M;
            isInitialized = true;
            Log.i(TAG, "XGBoost Model (MH-1M) initialized, threshold=" + xgbThreshold);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize XGBoost Model (MH-1M)", e);
            return false;
        }
    }

    /**
     * Parse LR model from JSON (MH-100K).
     */
    private void parseLRModel(JSONObject modelData) throws Exception {
        JSONArray coefArray = modelData.getJSONArray("coef");
        lrCoef = new float[coefArray.length()][];
        for (int i = 0; i < coefArray.length(); i++) {
            JSONArray row = coefArray.getJSONArray(i);
            lrCoef[i] = new float[row.length()];
            for (int j = 0; j < row.length(); j++) {
                lrCoef[i][j] = (float) row.getDouble(j);
            }
        }

        JSONArray interceptArray = modelData.getJSONArray("intercept");
        lrIntercept = new float[interceptArray.length()];
        for (int i = 0; i < interceptArray.length(); i++) {
            lrIntercept[i] = (float) interceptArray.getDouble(i);
        }

        JSONArray meanArray = modelData.getJSONArray("scaler_mean");
        lrScalerMean = new float[meanArray.length()];
        for (int i = 0; i < meanArray.length(); i++) {
            lrScalerMean[i] = (float) meanArray.getDouble(i);
        }

        JSONArray scaleArray = modelData.getJSONArray("scaler_scale");
        lrScalerScale = new float[scaleArray.length()];
        for (int i = 0; i < scaleArray.length(); i++) {
            lrScalerScale[i] = (float) scaleArray.getDouble(i);
        }

        // Set common scaler for backward compatibility
        scalerMean = lrScalerMean;
        scalerScale = lrScalerScale;
        threshold = lrThreshold;
    }

    /**
     * Parse XGBoost model from JSON (MH-1M) - pure Java tree parsing.
     */
    private void parseXGBoostModel(JSONObject modelData) throws Exception {
        // XGBoost model in JSON format contains trees array
        JSONArray treesArray = modelData.getJSONArray("trees");
        int numTrees = treesArray.length();
        xgbTrees = new XGBTree[totalNodes(treesArray)];
        int[] treeIndex = {0};
        parseTrees(treesArray, treeIndex);
    }

    private int totalNodes(JSONArray treesArray) throws JSONException {
        int total = 0;
        for (int i = 0; i < treesArray.length(); i++) {
            total += countNodes(treesArray.getJSONObject(i));
        }
        return total;
    }

    private int countNodes(JSONObject tree) throws JSONException {
        if (tree.has("leaf")) return 1;
        return 1 + countNodes(tree.getJSONObject("left")) + countNodes(tree.getJSONObject("right"));
    }

    private void parseTrees(JSONArray treesArray, int[] treeIndex) throws JSONException {
        for (int t = 0; t < treesArray.length(); t++) {
            parseTree(treesArray.getJSONObject(t), treeIndex);
        }
    }

    private void parseTree(JSONObject node, int[] treeIndex) throws JSONException {
        int idx = treeIndex[0]++;
        XGBTree tree = new XGBTree();

        if (node.has("leaf")) {
            tree.isLeaf = true;
            tree.leafValue = (float) node.getDouble("leaf");
        } else {
            tree.isLeaf = false;
            tree.featureIdx = node.getInt("split_feature");
            tree.splitCondition = (float) node.getDouble("split_condition");
            tree.leftChild = treeIndex[0];
            parseTree(node.getJSONObject("left"), treeIndex);
            tree.rightChild = treeIndex[0];
            parseTree(node.getJSONObject("right"), treeIndex);
        }
        xgbTrees[idx] = tree;
    }

    /**
     * Check if scanner is initialized.
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Get current model type.
     */
    public String getCurrentModelType() {
        return currentModelType;
    }

    /**
     * 按当前模型类型分派特征提取：LR(mh100k) 38 维 / XGBoost(mh1m) 43 维。
     */
    public float[] extractFeatures(PackageInfo pkg, PackageManager pm) {
        if (MODEL_MH1M.equals(currentModelType)) {
            return extractMh1mFeatures(pkg, pm);
        }
        return extractLrFeatures(pkg, pm);
    }

    /**
     * Extract 38 features for the LR (v3) pipeline.
     * Layout: 15 permission combos (order == training) + 3 context + 20 DEX API categories.
     * 与 ml/train/train_real_pipeline.py 的特征顺序必须逐一对应。
     */
    private float[] extractLrFeatures(PackageInfo pkg, PackageManager pm) {
        float[] features = new float[38];

        // Get permissions
        Set<String> permSet = new HashSet<>();
        if (pkg.requestedPermissions != null) {
            permSet.addAll(Arrays.asList(pkg.requestedPermissions));
        }

        // Features 0-14: Permission combination patterns (PERMISSION_COMBOS 为有序 Map)
        int featureIdx = 0;
        for (Map.Entry<String, String[]> entry : PERMISSION_COMBOS.entrySet()) {
            String[] comboPerms = entry.getValue();
            boolean allPresent = true;
            for (String perm : comboPerms) {
                if (!permSet.contains(perm)) {
                    allPresent = false;
                    break;
                }
            }
            features[featureIdx] = allPresent ? 1.0f : 0.0f;
            featureIdx++;
        }

        // Features 15-17: context (仅保留可在真实训练集还原的 3 项)
        int permCount = pkg.requestedPermissions != null ? pkg.requestedPermissions.length : 0;
        features[15] = Math.min(permCount / 50.0f, 1.0f);

        int dangerousCount = countDangerousPermissions(permSet);
        features[16] = Math.min(dangerousCount / 20.0f, 1.0f);

        boolean hasInternet = permSet.contains("android.permission.INTERNET");
        features[17] = hasInternet ? 1.0f : 0.0f;

        // Features 18-37: API patterns from DEX parsing
        Set<String> apiCalls = DexParser.extractApiCalls(context, pkg.packageName);
        float[] apiFeatures = DexParser.getApiFeatureVector(apiCalls);
        System.arraycopy(apiFeatures, 0, features, 18, 20);

        return features;
    }

    /**
     * Extract 43 features for the mh1m (XGBoost) pipeline.
     * 顺序严格对应 assets/features_mh1m.json：
     *   0-19 权限 / 20-27 Intent / 28-32 opcode / 33-42 APICall
     * 注：opcode 5 维在原训练数据中近乎恒为 1（均值 0.98~0.999），此处近似填 1。
     */
    private float[] extractMh1mFeatures(PackageInfo pkg, PackageManager pm) {
        float[] f = new float[43];

        Set<String> perms = new HashSet<>();
        if (pkg.requestedPermissions != null) {
            perms.addAll(Arrays.asList(pkg.requestedPermissions));
        }
        // 0-19 permissions
        for (int i = 0; i < MH1M_PERMS.length; i++) {
            f[i] = perms.contains(MH1M_PERMS[i]) ? 1.0f : 0.0f;
        }

        DexParser.DexSignals sig = DexParser.extractSignals(context, pkg.packageName);

        // 20-27 intents (DEX 字符串池)
        for (int i = 0; i < MH1M_INTENTS.length; i++) {
            f[20 + i] = sig.strings.contains(MH1M_INTENTS[i]) ? 1.0f : 0.0f;
        }

        // 28-32 opcodes (DEX 指令流扫描: const-string/invoke-virtual/invoke-static/move-result-object/if-eqz)
        f[28] = sig.opcodes.contains(0x1a) ? 1.0f : 0.0f;
        f[29] = sig.opcodes.contains(0x6e) ? 1.0f : 0.0f;
        f[30] = sig.opcodes.contains(0x71) ? 1.0f : 0.0f;
        f[31] = sig.opcodes.contains(0x0c) ? 1.0f : 0.0f;
        f[32] = sig.opcodes.contains(0x38) ? 1.0f : 0.0f;

        // 33-42 apicalls (DEX method_ids: 类描述符->方法名)
        for (int i = 0; i < MH1M_APIS.length; i++) {
            f[33 + i] = sig.methodRefs.contains(MH1M_APIS[i]) ? 1.0f : 0.0f;
        }

        return f;
    }

    // mh1m 特征签名（顺序 == features_mh1m.json）
    private static final String[] MH1M_PERMS = {
        "android.permission.INTERNET", "android.permission.READ_SMS", "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS", "android.permission.BROADCAST_SMS", "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS", "android.permission.READ_PHONE_STATE", "android.permission.READ_CALL_LOG",
        "android.permission.CALL_PHONE", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.GET_ACCOUNTS", "android.permission.PROCESS_OUTGOING_CALLS"
    };
    private static final String[] MH1M_INTENTS = {
        "android.intent.action.BOOT_COMPLETED", "android.intent.action.PACKAGE_ADDED",
        "android.intent.action.ACTION_POWER_CONNECTED", "android.intent.action.USER_PRESENT",
        "android.intent.action.NEW_OUTGOING_CALL", "android.intent.action.ACTION_SHUTDOWN",
        "android.net.conn.CONNECTIVITY_CHANGE", "android.intent.action.PHONE_STATE"
    };
    private static final String[] MH1M_APIS = {
        "Landroid/telephony/SmsManager;->sendTextMessage",
        "Landroid/telephony/TelephonyManager;->getDeviceId",
        "Landroid/telephony/TelephonyManager;->getSubscriberId",
        "Landroid/content/pm/PackageManager;->getInstalledPackages",
        "Landroid/content/pm/PackageManager;->queryIntentActivities",
        "Landroid/webkit/WebView;->loadData",
        "Landroid/net/wifi/WifiManager;->getConnectionInfo",
        "Landroid/telephony/SmsManager;->sendMultipartTextMessage",
        "Landroid/content/pm/PackageManager;->queryIntentServices",
        "Landroid/telephony/TelephonyManager;->getLine1Number"
    };

    private int countDangerousPermissions(Set<String> perms) {
        int count = 0;
        String[] dangerous = {
            "android.permission.READ_SMS", "android.permission.SEND_SMS",
            "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE",
            "android.permission.READ_CONTACTS", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_CALL_LOG", "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.READ_CALENDAR", "android.permission.BODY_SENSORS"
        };
        for (String d : dangerous) {
            if (perms.contains(d)) count++;
        }
        return count;
    }

    /**
     * Classify features using the loaded model (LR or XGBoost).
     */
    public float classify(float[] features) {
        if (!isInitialized) {
            return 0.0f;
        }

        try {
            // Normalize features
            float[] normalized = new float[features.length];
            float[] mean = scalerMean != null ? scalerMean : (MODEL_MH100K.equals(currentModelType) ? lrScalerMean : xgbScalerMean);
            float[] scale = scalerScale != null ? scalerScale : (MODEL_MH100K.equals(currentModelType) ? lrScalerScale : xgbScalerScale);

            for (int i = 0; i < features.length; i++) {
                if (mean != null && scale != null && i < mean.length && i < scale.length && scale[i] != 0) {
                    normalized[i] = (features[i] - mean[i]) / scale[i];
                } else {
                    normalized[i] = features[i];
                }
            }

            if (MODEL_MH1M.equals(currentModelType)) {
                // XGBoost prediction - traverse all trees
                float score = 0.0f;
                for (XGBTree tree : xgbTrees) {
                    if (tree != null) {
                        score += tree.predict(normalized, xgbTrees);
                    }
                }
                // Apply sigmoid
                return 1.0f / (1.0f + (float) Math.exp(-score));
            } else {
                // Logistic Regression
                float logit = lrIntercept[0];
                for (int i = 0; i < lrCoef[0].length; i++) {
                    logit += lrCoef[0][i] * normalized[i];
                }
                return 1.0f / (1.0f + (float) Math.exp(-logit));
            }

        } catch (Exception e) {
            Log.e(TAG, "Classification failed", e);
        }

        return 0.0f;
    }

    /**
     * Get ML detection result for a package.
     */
    public MlResult detect(PackageInfo pkg, PackageManager pm) {
        if (!isInitialized) {
            return new MlResult(0, "ML model not loaded");
        }

        float[] features = extractFeatures(pkg, null);
        float probability = classify(features);

        int score = (int) (probability * 100);

        String reason;
        float currentThreshold = MODEL_MH1M.equals(currentModelType) ? xgbThreshold : lrThreshold;
        if (probability >= currentThreshold) {
            reason = String.format("ML model detected malware (confidence: %.1f%%)", probability * 100);
        } else {
            reason = String.format("ML model classified as benign (confidence: %.1f%%)", (1 - probability) * 100);
        }

        return new MlResult(score, reason);
    }

    /**
     * Get feature names for debugging.
     */
    public String[] getFeatureNames() {
        return FEATURE_NAMES.clone();
    }

    /**
     * Cleanup resources.
     */
    public void close() {
        lrModelData = null;
        lrMetadata = null;
        lrCoef = null;
        lrIntercept = null;
        lrScalerMean = null;
        lrScalerScale = null;
        xgbTrees = null;
        scalerMean = null;
        scalerScale = null;
        isInitialized = false;
    }

    private String loadAsset(Context context, String filename) throws IOException {
        try (InputStream is = context.getAssets().open(filename)) {
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            return new String(buffer);
        }
    }

    /**
     * ML detection result.
     */
    public static class MlResult {
        public final int score;
        public final String reason;

        public MlResult(int score, String reason) {
            this.score = score;
            this.reason = reason;
        }
    }
}