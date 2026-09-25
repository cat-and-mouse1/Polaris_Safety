package com.polaris.app.scan;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

public class MlScannerTest {

    private MlScanner scanner;

    @Before
    public void setUp() {
        scanner = new MlScanner();
    }

    @Test
    public void testFeatureExtraction() {
        android.content.pm.PackageInfo pkg = new android.content.pm.PackageInfo();
        pkg.requestedPermissions = new String[]{
            "android.permission.INTERNET",
            "android.permission.WAKE_LOCK"
        };

        float[] features = scanner.extractFeatures(pkg, null);

        // WAKE_LOCK maps to index 0
        assertEquals(1.0f, features[0], 0.001f);
        // INTERNET maps to index 4
        assertEquals(1.0f, features[4], 0.001f);
        // WRITE_EXTERNAL_STORAGE not granted
        assertEquals(0.0f, features[1], 0.001f);
        // ACCESS_NETWORK_STATE not granted
        assertEquals(0.0f, features[2], 0.001f);
        // WRITE_SETTINGS not granted
        assertEquals(0.0f, features[3], 0.001f);
    }

    @Test
    public void testFeatureExtractionNoPermissions() {
        android.content.pm.PackageInfo pkg = new android.content.pm.PackageInfo();
        pkg.requestedPermissions = null;

        float[] features = scanner.extractFeatures(pkg, null);

        // All permission features should be 0
        assertEquals(0.0f, features[0], 0.001f);
        assertEquals(0.0f, features[4], 0.001f);
    }

    @Test
    public void testFeatureArraySize() {
        android.content.pm.PackageInfo pkg = new android.content.pm.PackageInfo();
        pkg.requestedPermissions = new String[]{
            "android.permission.INTERNET"
        };

        float[] features = scanner.extractFeatures(pkg, null);
        assertEquals(24, features.length);
    }

    @Test
    public void testMlResult() {
        MlScanner.MlResult result = new MlScanner.MlResult(75, "Test reason");
        assertEquals(75, result.score);
        assertEquals("Test reason", result.reason);
    }

    @Test
    public void testMlResultNotInitialized() {
        // Scanner is not initialized, detect should return score 0
        android.content.pm.PackageInfo pkg = new android.content.pm.PackageInfo();
        pkg.requestedPermissions = new String[]{
            "android.permission.INTERNET"
        };

        MlScanner.MlResult result = scanner.detect(pkg, null);
        assertEquals(0, result.score);
        assertEquals("ML model not loaded", result.reason);
    }

    @Test
    public void testClassifyNotInitialized() {
        // Scanner not initialized, classify should return 0
        float[] features = new float[24];
        features[4] = 1.0f; // INTERNET
        float probability = scanner.classify(features);
        assertEquals(0.0f, probability, 0.001f);
    }

    @Test
    public void testIsInitiallyNotInitialized() {
        assertFalse(scanner.isInitialized());
    }

    @Test
    public void testCloseResetsState() {
        scanner.close();
        assertFalse(scanner.isInitialized());
    }
}
