#!/usr/bin/env python3
"""
Simplified ML training for Polaris Safety v2.
Generates synthetic training data based on malware patterns.
"""

import json
import numpy as np
from pathlib import Path
from sklearn.model_selection import train_test_split
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
from sklearn.pipeline import Pipeline

# ============================================================================
# Feature Definitions
# ============================================================================

# Permission combination patterns (15 features)
PERM_COMBO_NAMES = [
    "SMS_MALWARE", "PHONE_MALWARE", "CONTACTS_MALWARE",
    "LOCATION_MALWARE", "CAMERA_MALWARE", "STORAGE_MALWARE",
    "ADMIN_MALWARE", "OVERLAY_MALWARE", "CLIPBOARD_MALWARE",
    "CALENDAR_MALWARE", "SENSOR_MALWARE", "BLUETOOTH_MALWARE",
    "WIFI_MALWARE", "NFC_MALWARE", "NOTIFICATION_MALWARE"
]

# Context features (8 features)
CONTEXT_NAMES = [
    "is_system_app", "target_sdk_version", "min_sdk_version",
    "permission_count", "dangerous_perm_count", "app_category",
    "has_native_code", "has_internet_permission"
]

# API pattern features (20 features)
API_NAMES = [
    "reflection", "dynamic_load", "crypto", "network", "sms",
    "phone", "contacts", "location", "camera", "audio",
    "file", "shared_prefs", "intent", "service", "broadcast",
    "content_provider", "notification", "accessibility", "device_admin", "package"
]

ALL_FEATURES = [f"combo_{n}" for n in PERM_COMBO_NAMES] + \
               [f"ctx_{n}" for n in CONTEXT_NAMES] + \
               [f"api_{n}" for n in API_NAMES]

# ============================================================================
# Synthetic Data Generation
# ============================================================================

def generate_synthetic_data(n_samples=10000, seed=42):
    """Generate synthetic training data based on malware patterns."""
    np.random.seed(seed)
    
    X = []
    y = []
    
    for _ in range(n_samples):
        # Decide if this is malware or benign
        is_malware = np.random.random() < 0.3  # 30% malware
        
        features = np.zeros(43)
        
        if is_malware:
            # Malware: high scores in dangerous combinations
            # SMS malware (features 0-4)
            if np.random.random() < 0.6:
                features[0] = 1  # SMS combo
            if np.random.random() < 0.5:
                features[1] = 1  # Phone combo
            if np.random.random() < 0.4:
                features[2] = 1  # Contacts combo
            if np.random.random() < 0.5:
                features[3] = 1  # Location combo
            if np.random.random() < 0.3:
                features[4] = 1  # Camera combo
            if np.random.random() < 0.4:
                features[5] = 1  # Storage combo
            if np.random.random() < 0.3:
                features[6] = 1  # Admin combo
            if np.random.random() < 0.4:
                features[7] = 1  # Overlay combo
            
            # Context (malware often targets older SDK)
            features[15] = 0  # Not system app
            features[16] = np.random.uniform(0.5, 0.8)  # Target SDK
            features[17] = np.random.uniform(0.3, 0.6)  # Min SDK
            features[18] = np.random.uniform(0.3, 0.8)  # Permission count
            features[19] = np.random.uniform(0.2, 0.7)  # Dangerous perm count
            
            # API patterns (malware uses more suspicious APIs)
            features[23] = np.random.random() < 0.7  # reflection
            features[24] = np.random.random() < 0.6  # dynamic_load
            features[25] = np.random.random() < 0.5  # crypto
            features[26] = np.random.random() < 0.8  # network
            features[27] = np.random.random() < 0.6  # sms
            features[28] = np.random.random() < 0.5  # phone
            features[29] = np.random.random() < 0.4  # contacts
            features[30] = np.random.random() < 0.5  # location
            features[31] = np.random.random() < 0.3  # camera
            features[32] = np.random.random() < 0.3  # audio
            features[33] = np.random.random() < 0.7  # file
            features[34] = np.random.random() < 0.5  # shared_prefs
            features[35] = np.random.random() < 0.6  # intent
            features[36] = np.random.random() < 0.4  # service
            features[37] = np.random.random() < 0.5  # broadcast
            features[38] = np.random.random() < 0.3  # content_provider
            features[39] = np.random.random() < 0.4  # notification
            features[40] = np.random.random() < 0.3  # accessibility
            features[41] = np.random.random() < 0.2  # device_admin
            features[42] = np.random.random() < 0.5  # package
            
        else:
            # Benign: low scores in dangerous combinations
            # Only a few benign apps have these combos
            features[0] = np.random.random() < 0.1  # SMS (rare in benign)
            features[1] = np.random.random() < 0.15  # Phone
            features[2] = np.random.random() < 0.1  # Contacts
            features[3] = np.random.random() < 0.2  # Location
            features[4] = np.random.random() < 0.1  # Camera
            features[5] = np.random.random() < 0.3  # Storage (common)
            features[6] = np.random.random() < 0.05  # Admin (rare)
            features[7] = np.random.random() < 0.05  # Overlay (rare)
            
            # Context (benign apps vary)
            features[15] = np.random.random() < 0.3  # System app
            features[16] = np.random.uniform(0.6, 1.0)  # Target SDK (higher)
            features[17] = np.random.uniform(0.5, 0.8)  # Min SDK
            features[18] = np.random.uniform(0.1, 0.5)  # Permission count (lower)
            features[19] = np.random.uniform(0.1, 0.4)  # Dangerous perm count (lower)
            
            # API patterns (benign uses fewer suspicious APIs)
            features[23] = np.random.random() < 0.2  # reflection (rare)
            features[24] = np.random.random() < 0.1  # dynamic_load (rare)
            features[25] = np.random.random() < 0.2  # crypto
            features[26] = np.random.random() < 0.5  # network (common)
            features[27] = np.random.random() < 0.05  # sms (rare)
            features[28] = np.random.random() < 0.1  # phone
            features[29] = np.random.random() < 0.1  # contacts
            features[30] = np.random.random() < 0.15  # location
            features[31] = np.random.random() < 0.2  # camera
            features[32] = np.random.random() < 0.15  # audio
            features[33] = np.random.random() < 0.5  # file (common)
            features[34] = np.random.random() < 0.4  # shared_prefs
            features[35] = np.random.random() < 0.5  # intent (common)
            features[36] = np.random.random() < 0.3  # service
            features[37] = np.random.random() < 0.4  # broadcast
            features[38] = np.random.random() < 0.2  # content_provider
            features[39] = np.random.random() < 0.3  # notification
            features[40] = np.random.random() < 0.1  # accessibility (rare)
            features[41] = np.random.random() < 0.02  # device_admin (very rare)
            features[42] = np.random.random() < 0.4  # package
        
        X.append(features)
        y.append(1 if is_malware else 0)
    
    return np.array(X), np.array(y)


# ============================================================================
# Model Training
# ============================================================================

def train_model(X, y):
    """Train logistic regression model."""
    # Split data
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )
    
    # Create pipeline
    pipeline = Pipeline([
        ('scaler', StandardScaler()),
        ('model', LogisticRegression(
            max_iter=1000, 
            random_state=42, 
            class_weight='balanced'
        ))
    ])
    
    # Train
    pipeline.fit(X_train, y_train)
    
    # Evaluate
    y_pred = pipeline.predict(X_test)
    
    accuracy = accuracy_score(y_test, y_pred)
    precision = precision_score(y_test, y_pred, zero_division=0)
    recall = recall_score(y_test, y_pred, zero_division=0)
    f1 = f1_score(y_test, y_pred, zero_division=0)
    
    print(f"Accuracy:  {accuracy:.4f}")
    print(f"Precision: {precision:.4f}")
    print(f"Recall:    {recall:.4f}")
    print(f"F1 Score:  {f1:.4f}")
    
    return pipeline, accuracy, precision, recall, f1


# ============================================================================
# Model Export
# ============================================================================

def export_model(pipeline, accuracy, precision, recall, f1, output_dir):
    """Export trained model to JSON."""
    model = pipeline.named_steps['model']
    scaler = pipeline.named_steps['scaler']
    
    model_data = {
        "type": "logistic_regression",
        "coef": model.coef_.tolist(),
        "intercept": model.intercept_.tolist(),
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "feature_names": ALL_FEATURES,
        "n_features": len(ALL_FEATURES),
        "threshold": 0.5,
        "accuracy": accuracy,
        "f1_score": f1,
        "precision": precision,
        "recall": recall
    }
    
    # Save model
    model_file = output_dir / "malware_detector_v2.json"
    with open(model_file, 'w') as f:
        json.dump(model_data, f, indent=2)
    
    # Save metadata
    metadata = {
        "version": "2.0.0",
        "model_file": "malware_detector_v2.json",
        "type": "logistic_regression",
        "features": len(ALL_FEATURES),
        "threshold": 0.5,
        "accuracy": accuracy,
        "f1_score": f1,
        "precision": precision,
        "recall": recall,
        "trained_on": "Synthetic Data (Malware Patterns)",
        "training_date": "2026-08-31",
        "feature_names": ALL_FEATURES,
        "feature_groups": {
            "permission_combos": 15,
            "context": 8,
            "api_patterns": 20
        },
        "description": "Android malware detection model v2 with permission combos + context + API patterns"
    }
    
    metadata_file = output_dir / "malware_detector_v2_metadata.json"
    with open(metadata_file, 'w') as f:
        json.dump(metadata, f, indent=2)
    
    print(f"\nModel saved to: {model_file}")
    print(f"Metadata saved to: {metadata_file}")
    
    return model_file, metadata_file


# ============================================================================
# Main
# ============================================================================

def main():
    print("=" * 60)
    print("Polaris Safety ML Training v2")
    print("=" * 60)
    
    # Generate synthetic data
    print("\nGenerating synthetic training data...")
    X, y = generate_synthetic_data(n_samples=10000, seed=42)
    print(f"Generated {len(X)} samples")
    print(f"Malware: {np.sum(y == 1)}, Benign: {np.sum(y == 0)}")
    
    # Train model
    print("\nTraining model...")
    pipeline, accuracy, precision, recall, f1 = train_model(X, y)
    
    # Export model
    output_dir = Path(__file__).parent.parent.parent / "app" / "src" / "main" / "assets"
    output_dir.mkdir(parents=True, exist_ok=True)
    
    print("\nExporting model...")
    model_file, metadata_file = export_model(pipeline, accuracy, precision, recall, f1, output_dir)
    
    print("\n" + "=" * 60)
    print("Training Complete!")
    print("=" * 60)


if __name__ == "__main__":
    main()
