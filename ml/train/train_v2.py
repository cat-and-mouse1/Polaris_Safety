#!/usr/bin/env python3
"""
Polaris Safety ML Training Pipeline v2
New feature engineering: permission combos + context + DEX API calls
"""

import os
import sys
import json
import pickle
import hashlib
import logging
import numpy as np
import pandas as pd
from pathlib import Path
from typing import Dict, List, Tuple, Any
from dataclasses import dataclass
from sklearn.model_selection import train_test_split
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, confusion_matrix, classification_report
)
from sklearn.pipeline import Pipeline

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# ============================================================================
# Feature Definitions
# ============================================================================

@dataclass
class FeatureConfig:
    """Feature configuration for ML model."""
    
    # Permission combination patterns (malware signatures)
    PERMISSION_COMBOS: Dict[str, List[str]] = None
    
    # Context features
    CONTEXT_FEATURES: List[str] = None
    
    # DEX API call patterns
    API_PATTERNS: Dict[str, List[str]] = None
    
    def __post_init__(self):
        if self.PERMISSION_COMBOS is None:
            self.PERMISSION_COMBOS = {
                # SMS malware patterns
                "SMS_MALWARE": [
                    "android.permission.READ_SMS",
                    "android.permission.SEND_SMS",
                    "android.permission.RECEIVE_SMS",
                    "android.permission.RECEIVE_MMS",
                    "android.permission.RECEIVE_WAP_PUSH"
                ],
                # Phone malware patterns
                "PHONE_MALWARE": [
                    "android.permission.READ_PHONE_STATE",
                    "android.permission.CALL_PHONE",
                    "android.permission.PROCESS_OUTGOING_CALLS",
                    "android.permission.READ_CALL_LOG",
                    "android.permission.WRITE_CALL_LOG"
                ],
                # Contacts malware patterns
                "CONTACTS_MALWARE": [
                    "android.permission.READ_CONTACTS",
                    "android.permission.WRITE_CONTACTS",
                    "android.permission.GET_ACCOUNTS"
                ],
                # Location malware patterns
                "LOCATION_MALWARE": [
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.ACCESS_BACKGROUND_LOCATION"
                ],
                # Camera/Audio malware patterns
                "CAMERA_MALWARE": [
                    "android.permission.CAMERA",
                    "android.permission.RECORD_AUDIO"
                ],
                # Storage malware patterns
                "STORAGE_MALWARE": [
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.MANAGE_EXTERNAL_STORAGE"
                ],
                # Device admin malware patterns
                "ADMIN_MALWARE": [
                    "android.permission.BIND_DEVICE_ADMIN",
                    "android.permission.INSTALL_PACKAGES",
                    "android.permission.DELETE_PACKAGES",
                    "android.permission.BIND_ACCESSIBILITY_SERVICE"
                ],
                # Overlay malware patterns
                "OVERLAY_MALWARE": [
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    "android.permission.BIND_ACCESSIBILITY_SERVICE"
                ],
                # Clipboard malware patterns
                "CLIPBOARD_MALWARE": [
                    "android.permission.READ_CLIPBOARD",
                    "android.permission.WRITE_CLIPBOARD"
                ],
                # Calendar malware patterns
                "CALENDAR_MALWARE": [
                    "android.permission.READ_CALENDAR",
                    "android.permission.WRITE_CALENDAR"
                ],
                # Sensor malware patterns
                "SENSOR_MALWARE": [
                    "android.permission.BODY_SENSORS",
                    "android.permission.ACTIVITY_RECOGNITION"
                ],
                # Bluetooth malware patterns
                "BLUETOOTH_MALWARE": [
                    "android.permission.BLUETOOTH",
                    "android.permission.BLUETOOTH_ADMIN",
                    "android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_CONNECT"
                ],
                # WiFi malware patterns
                "WIFI_MALWARE": [
                    "android.permission.ACCESS_WIFI_STATE",
                    "android.permission.CHANGE_WIFI_STATE",
                    "android.permission.CHANGE_NETWORK_STATE"
                ],
                # NFC malware patterns
                "NFC_MALWARE": [
                    "android.permission.NFC"
                ],
                # Notification malware patterns
                "NOTIFICATION_MALWARE": [
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.RECEIVE_BOOT_COMPLETED"
                ]
            }
        
        if self.CONTEXT_FEATURES is None:
            self.CONTEXT_FEATURES = [
                "is_system_app",           # System app vs third-party
                "target_sdk_version",      # Target SDK version
                "min_sdk_version",         # Minimum SDK version
                "permission_count",        # Total permissions requested
                "dangerous_perm_count",    # Dangerous permissions count
                "app_category",            # App category (encoded)
                "has_native_code",         # Has native libraries
                "has_internet_permission"  # Has internet permission
            ]
        
        if self.API_PATTERNS is None:
            self.API_PATTERNS = {
                # Reflection (common in malware)
                "reflection": [
                    "java.lang.reflect.Method.invoke",
                    "java.lang.reflect.Field.setAccessible",
                    "java.lang.Class.forName"
                ],
                # Dynamic class loading
                "dynamic_load": [
                    "dalvik.system.DexClassLoader",
                    "dalvik.system.PathClassLoader",
                    "java.lang.ClassLoader.loadClass"
                ],
                # Crypto operations
                "crypto": [
                    "javax.crypto.Cipher",
                    "javax.crypto.SecretKeySpec",
                    "java.security.MessageDigest"
                ],
                # Network operations
                "network": [
                    "java.net.HttpURLConnection",
                    "java.net.URL.openConnection",
                    "org.apache.http.impl.client.DefaultHttpClient"
                ],
                # SMS operations
                "sms": [
                    "android.telephony.SmsManager",
                    "android.telephony.SmsMessage"
                ],
                # Phone operations
                "phone": [
                    "android.telephony.TelephonyManager",
                    "android.telephony.PhoneStateListener"
                ],
                # Contacts operations
                "contacts": [
                    "android.content.ContentResolver",
                    "android.provider.ContactsContract"
                ],
                # Location operations
                "location": [
                    "android.location.LocationManager",
                    "android.location.LocationListener"
                ],
                # Camera operations
                "camera": [
                    "android.hardware.Camera",
                    "android.hardware.camera2.CameraManager"
                ],
                # Audio operations
                "audio": [
                    "android.media.MediaRecorder",
                    "android.media.AudioRecord"
                ],
                # File operations
                "file": [
                    "java.io.File",
                    "java.io.FileInputStream",
                    "java.io.FileOutputStream"
                ],
                # SharedPreferences operations
                "shared_prefs": [
                    "android.content.SharedPreferences",
                    "android.content.SharedPreferences$Editor"
                ],
                # Intent operations
                "intent": [
                    "android.content.Intent",
                    "android.content.IntentFilter"
                ],
                # Service operations
                "service": [
                    "android.app.Service",
                    "android.app.IntentService"
                ],
                # Broadcast operations
                "broadcast": [
                    "android.content.BroadcastReceiver",
                    "android.support.v4.content.LocalBroadcastManager"
                ],
                # Content Provider operations
                "content_provider": [
                    "android.content.ContentProvider",
                    "android.content.ContentUris"
                ],
                # Notification operations
                "notification": [
                    "android.app.NotificationManager",
                    "android.app.NotificationChannel"
                ],
                # Accessibility operations
                "accessibility": [
                    "android.accessibilityservice.AccessibilityService",
                    "android.view.accessibility.AccessibilityEvent"
                ],
                # Device Admin operations
                "device_admin": [
                    "android.app.admin.DeviceAdminReceiver",
                    "android.app.admin.DevicePolicyManager"
                ],
                # Package operations
                "package": [
                    "android.content.pm.PackageManager",
                    "android.content.pm.PackageInfo"
                ]
            }


# ============================================================================
# Feature Extractor
# ============================================================================

class FeatureExtractor:
    """Extract features from MH-100K dataset."""
    
    def __init__(self, config: FeatureConfig):
        self.config = config
        self.feature_names = []
        self._build_feature_names()
    
    def _build_feature_names(self):
        """Build feature names list."""
        self.feature_names = []
        
        # Permission combination features
        for combo_name in self.config.PERMISSION_COMBOS.keys():
            self.feature_names.append(f"combo_{combo_name}")
        
        # Context features
        for ctx_feat in self.config.CONTEXT_FEATURES:
            self.feature_names.append(f"ctx_{ctx_feat}")
        
        # API pattern features
        for api_name in self.config.API_PATTERNS.keys():
            self.feature_names.append(f"api_{api_name}")
    
    def extract_permission_combo_features(self, permissions: List[str]) -> Dict[str, int]:
        """Extract permission combination features."""
        features = {}
        perm_set = set(permissions)
        
        for combo_name, combo_perms in self.config.PERMISSION_COMBOS.items():
            # Check if all permissions in combo are present
            if all(p in perm_set for p in combo_perms):
                features[f"combo_{combo_name}"] = 1
            else:
                features[f"combo_{combo_name}"] = 0
        
        return features
    
    def extract_context_features(self, metadata: Dict[str, Any]) -> Dict[str, float]:
        """Extract context features from metadata."""
        features = {}
        
        # is_system_app
        features["ctx_is_system_app"] = 1 if metadata.get("is_system", False) else 0
        
        # target_sdk_version (normalized)
        target_sdk = metadata.get("target_sdk", 28)
        features["ctx_target_sdk_version"] = min(target_sdk / 33.0, 1.0)
        
        # min_sdk_version (normalized)
        min_sdk = metadata.get("min_sdk", 21)
        features["ctx_min_sdk_version"] = min(min_sdk / 33.0, 1.0)
        
        # permission_count (normalized)
        perm_count = metadata.get("permission_count", 0)
        features["ctx_permission_count"] = min(perm_count / 50.0, 1.0)
        
        # dangerous_perm_count (normalized)
        dangerous_count = metadata.get("dangerous_perm_count", 0)
        features["ctx_dangerous_perm_count"] = min(dangerous_count / 20.0, 1.0)
        
        # app_category (encoded as integer)
        category_map = {
            "tools": 0, "social": 1, "game": 2, "productivity": 3,
            "entertainment": 4, "communication": 5, "finance": 6,
            "health": 7, "education": 8, "shopping": 9, "other": 10
        }
        category = metadata.get("app_category", "other")
        features["ctx_app_category"] = category_map.get(category, 10) / 10.0
        
        # has_native_code
        features["ctx_has_native_code"] = 1 if metadata.get("has_native_code", False) else 0
        
        # has_internet_permission
        features["ctx_has_internet_permission"] = 1 if "android.permission.INTERNET" in permissions else 0
        
        return features
    
    def extract_api_features(self, api_calls: List[str]) -> Dict[str, int]:
        """Extract API call pattern features."""
        features = {}
        api_set = set(api_calls)
        
        for api_name, api_patterns in self.config.API_PATTERNS.items():
            # Check if any pattern is present
            if any(p in api_set for p in api_patterns):
                features[f"api_{api_name}"] = 1
            else:
                features[f"api_{api_name}"] = 0
        
        return features
    
    def extract_features(self, permissions: List[str], metadata: Dict[str, Any], 
                        api_calls: List[str] = None) -> Dict[str, float]:
        """Extract all features from a sample."""
        features = {}
        
        # Permission combination features
        features.update(self.extract_permission_combo_features(permissions))
        
        # Context features
        features.update(self.extract_context_features(metadata))
        
        # API features (if available)
        if api_calls:
            features.update(self.extract_api_features(api_calls))
        else:
            # Default to 0 for API features
            for api_name in self.config.API_PATTERNS.keys():
                features[f"api_{api_name}"] = 0
        
        return features
    
    def get_feature_names(self) -> List[str]:
        """Get list of feature names."""
        return self.feature_names.copy()


# ============================================================================
# Data Loader
# ============================================================================

class MH100KDataLoader:
    """Load and preprocess MH-100K dataset."""
    
    def __init__(self, data_dir: str):
        self.data_dir = Path(data_dir)
        self.raw_dir = self.data_dir / "raw"
        self.processed_dir = self.data_dir / "processed"
        
        # Create directories
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.processed_dir.mkdir(parents=True, exist_ok=True)
    
    def download_dataset(self) -> Tuple[pd.DataFrame, pd.DataFrame]:
        """Download MH-100K dataset from HuggingFace."""
        import ssl
        import urllib.request
        
        # Bypass SSL verification for HuggingFace
        ssl._create_default_https_context = ssl._create_unverified_context
        
        # Download labels
        labels_url = "https://huggingface.co/datasets/arielnlee/MalHunt-100k/resolve/main/mh100-labels.csv"
        labels_file = self.raw_dir / "mh100-labels.csv"
        
        if not labels_file.exists():
            logger.info("Downloading labels...")
            urllib.request.urlretrieve(labels_url, labels_file)
        
        # Download feature names
        feature_names_url = "https://huggingface.co/datasets/arielnlee/MalHunt-100k/resolve/main/feature_names.csv"
        feature_names_file = self.raw_dir / "feature_names.csv"
        
        if not feature_names_file.exists():
            logger.info("Downloading feature names...")
            urllib.request.urlretrieve(feature_names_url, feature_names_file)
        
        # Load labels
        labels_df = pd.read_csv(labels_file)
        feature_names_df = pd.read_csv(feature_names_file)
        
        logger.info(f"Loaded {len(labels_df)} samples with {len(feature_names_df)} features")
        
        return labels_df, feature_names_df
    
    def load_raw_features(self, labels_df: pd.DataFrame, feature_names_df: pd.DataFrame) -> pd.DataFrame:
        """Load raw features from parquet file."""
        import ssl
        import urllib.request
        
        # Bypass SSL verification
        ssl._create_default_https_context = ssl._create_unverified_context
        
        # Download parquet file if not exists
        parquet_url = "https://huggingface.co/datasets/arielnlee/MalHunt-100k/resolve/main/mh100.parquet"
        parquet_file = self.raw_dir / "mh100.parquet"
        
        if not parquet_file.exists():
            logger.info("Downloading parquet file...")
            urllib.request.urlretrieve(parquet_url, parquet_file)
        
        # Load parquet
        logger.info("Loading parquet file...")
        features_df = pd.read_parquet(parquet_file)
        
        logger.info(f"Loaded features shape: {features_df.shape}")
        
        return features_df
    
    def process_dataset(self) -> Tuple[np.ndarray, np.ndarray, List[str]]:
        """Process MH-100K dataset into new feature format."""
        # Load raw data
        labels_df, feature_names_df = self.download_dataset()
        features_df = self.load_raw_features(labels_df, feature_names_df)
        
        # Extract feature names
        raw_feature_names = feature_names_df['feature_name'].tolist()
        
        # Initialize feature extractor
        config = FeatureConfig()
        extractor = FeatureExtractor(config)
        new_feature_names = extractor.get_feature_names()
        
        # Process each sample
        logger.info("Processing samples...")
        processed_features = []
        
        for idx in range(len(features_df)):
            # Get raw features for this sample
            raw_features = features_df.iloc[idx].values
            
            # Extract permissions (first 5 features are permissions)
            permissions = []
            perm_names = [
                "android.permission.WAKE_LOCK",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.WRITE_SETTINGS",
                "android.permission.INTERNET"
            ]
            for i, perm in enumerate(perm_names):
                if i < len(raw_features) and raw_features[i] == 1:
                    permissions.append(perm)
            
            # Create metadata
            metadata = {
                "is_system": False,  # Will be updated if available
                "target_sdk": 28,    # Default
                "min_sdk": 21,       # Default
                "permission_count": len(permissions),
                "dangerous_perm_count": sum(1 for p in permissions if "android.permission." in p),
                "app_category": "other",
                "has_native_code": False,
                "has_internet_permission": "android.permission.INTERNET" in permissions
            }
            
            # Extract new features
            new_features = extractor.extract_features(permissions, metadata)
            
            # Convert to array
            feature_vector = [new_features.get(name, 0) for name in new_feature_names]
            processed_features.append(feature_vector)
            
            if (idx + 1) % 1000 == 0:
                logger.info(f"Processed {idx + 1}/{len(features_df)} samples")
        
        # Convert to numpy array
        X = np.array(processed_features)
        y = labels_df['label'].values
        
        logger.info(f"Final feature matrix shape: {X.shape}")
        logger.info(f"Label distribution: {np.sum(y == 0)} benign, {np.sum(y == 1)} malware")
        
        return X, y, new_feature_names


# ============================================================================
# Model Trainer
# ============================================================================

class ModelTrainer:
    """Train and evaluate ML models."""
    
    def __init__(self, output_dir: str):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
    
    def train_models(self, X: np.ndarray, y: np.ndarray, feature_names: List[str]) -> Dict[str, Any]:
        """Train multiple models and select the best one."""
        # Split data
        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=0.2, random_state=42, stratify=y
        )
        
        logger.info(f"Training set: {len(X_train)} samples")
        logger.info(f"Test set: {len(X_test)} samples")
        
        # Define models
        models = {
            "logistic_regression": LogisticRegression(
                max_iter=1000, random_state=42, class_weight='balanced'
            ),
            "random_forest": RandomForestClassifier(
                n_estimators=100, random_state=42, class_weight='balanced', n_jobs=-1
            ),
            "gradient_boosting": GradientBoostingClassifier(
                n_estimators=100, random_state=42
            )
        }
        
        # Train and evaluate each model
        results = {}
        best_model = None
        best_score = 0
        
        for name, model in models.items():
            logger.info(f"\nTraining {name}...")
            
            # Create pipeline with scaling
            pipeline = Pipeline([
                ('scaler', StandardScaler()),
                ('model', model)
            ])
            
            # Train
            pipeline.fit(X_train, y_train)
            
            # Predict
            y_pred = pipeline.predict(X_test)
            y_proba = pipeline.predict_proba(X_test)[:, 1] if hasattr(model, 'predict_proba') else y_pred
            
            # Calculate metrics
            accuracy = accuracy_score(y_test, y_pred)
            precision = precision_score(y_test, y_pred, zero_division=0)
            recall = recall_score(y_test, y_pred, zero_division=0)
            f1 = f1_score(y_test, y_pred, zero_division=0)
            
            try:
                auc = roc_auc_score(y_test, y_proba)
            except:
                auc = 0.5
            
            # Confusion matrix
            cm = confusion_matrix(y_test, y_pred)
            
            # Store results
            results[name] = {
                "accuracy": accuracy,
                "precision": precision,
                "recall": recall,
                "f1": f1,
                "auc": auc,
                "confusion_matrix": cm.tolist(),
                "model": pipeline
            }
            
            logger.info(f"{name} Results:")
            logger.info(f"  Accuracy:  {accuracy:.4f}")
            logger.info(f"  Precision: {precision:.4f}")
            logger.info(f"  Recall:    {recall:.4f}")
            logger.info(f"  F1 Score:  {f1:.4f}")
            logger.info(f"  AUC:       {auc:.4f}")
            logger.info(f"  Confusion Matrix:\n{cm}")
            
            # Select best model based on F1 score
            if f1 > best_score:
                best_score = f1
                best_model = name
        
        logger.info(f"\nBest model: {best_model} (F1: {best_score:.4f})")
        
        return results, best_model
    
    def export_model(self, model_pipeline: Pipeline, feature_names: List[str], 
                    metadata: Dict[str, Any]) -> Dict[str, Any]:
        """Export trained model to JSON format."""
        # Get the actual model from pipeline
        model = model_pipeline.named_steps['model']
        scaler = model_pipeline.named_steps['scaler']
        
        # Export model weights
        if hasattr(model, 'coef_'):
            # Logistic Regression
            model_data = {
                "type": "logistic_regression",
                "coef": model.coef_.tolist(),
                "intercept": model.intercept_.tolist(),
                "scaler_mean": scaler.mean_.tolist(),
                "scaler_scale": scaler.scale_.tolist(),
                "feature_names": feature_names,
                "n_features": len(feature_names)
            }
        elif hasattr(model, 'feature_importances_'):
            # Random Forest or Gradient Boosting
            model_data = {
                "type": "tree_ensemble",
                "n_estimators": model.n_estimators,
                "feature_importances": model.feature_importances_.tolist(),
                "classes": model.classes_.tolist(),
                "scaler_mean": scaler.mean_.tolist(),
                "scaler_scale": scaler.scale_.tolist(),
                "feature_names": feature_names,
                "n_features": len(feature_names)
            }
        
        # Add metadata
        model_data.update(metadata)
        
        return model_data
    
    def save_model(self, model_data: Dict[str, Any], model_name: str = "malware_detector_v2"):
        """Save model to JSON file."""
        output_file = self.output_dir / f"{model_name}.json"
        
        with open(output_file, 'w') as f:
            json.dump(model_data, f, indent=2)
        
        logger.info(f"Model saved to {output_file}")
        
        # Also save metadata
        metadata_file = self.output_dir / f"{model_name}_metadata.json"
        metadata = {
            "version": "2.0.0",
            "model_file": f"{model_name}.json",
            "type": model_data.get("type"),
            "features": model_data.get("n_features"),
            "accuracy": model_data.get("accuracy"),
            "f1_score": model_data.get("f1_score"),
            "precision": model_data.get("precision"),
            "recall": model_data.get("recall"),
            "auc": model_data.get("auc"),
            "trained_on": "MH-100K",
            "training_date": pd.Timestamp.now().strftime("%Y-%m-%d"),
            "feature_names": model_data.get("feature_names"),
            "description": "Android malware detection model v2 with permission combos + context + API patterns"
        }
        
        with open(metadata_file, 'w') as f:
            json.dump(metadata, f, indent=2)
        
        logger.info(f"Metadata saved to {metadata_file}")
        
        return output_file, metadata_file


# ============================================================================
# Main Training Pipeline
# ============================================================================

def main():
    """Main training pipeline."""
    # Paths
    project_root = Path(__file__).parent.parent.parent
    data_dir = project_root / "ml" / "data"
    output_dir = project_root / "app" / "src" / "main" / "assets"
    
    logger.info("=" * 60)
    logger.info("Polaris Safety ML Training Pipeline v2")
    logger.info("=" * 60)
    
    # Step 1: Load and process dataset
    logger.info("\nStep 1: Loading and processing dataset...")
    loader = MH100KDataLoader(data_dir)
    X, y, feature_names = loader.process_dataset()
    
    # Step 2: Train models
    logger.info("\nStep 2: Training models...")
    trainer = ModelTrainer(output_dir)
    results, best_model_name = trainer.train_models(X, y, feature_names)
    
    # Step 3: Get best model
    best_model = results[best_model_name]["model"]
    
    # Step 4: Export model
    logger.info("\nStep 3: Exporting model...")
    metadata = {
        "accuracy": results[best_model_name]["accuracy"],
        "f1_score": results[best_model_name]["f1_score"],
        "precision": results[best_model_name]["precision"],
        "recall": results[best_model_name]["recall"],
        "auc": results[best_model_name]["auc"],
        "best_model": best_model_name,
        "feature_groups": {
            "permission_combos": 15,
            "context": 8,
            "api_patterns": 20
        }
    }
    
    model_data = trainer.export_model(best_model, feature_names, metadata)
    
    # Step 5: Save model
    output_file, metadata_file = trainer.save_model(model_data)
    
    # Step 6: Print summary
    logger.info("\n" + "=" * 60)
    logger.info("Training Complete!")
    logger.info("=" * 60)
    logger.info(f"Best Model: {best_model_name}")
    logger.info(f"Accuracy:   {results[best_model_name]['accuracy']:.4f}")
    logger.info(f"F1 Score:   {results[best_model_name]['f1_score']:.4f}")
    logger.info(f"AUC:        {results[best_model_name]['auc']:.4f}")
    logger.info(f"Features:   {len(feature_names)}")
    logger.info(f"Model File: {output_file}")
    logger.info(f"Metadata:   {metadata_file}")
    
    return results, best_model_name


if __name__ == "__main__":
    results, best_model = main()
