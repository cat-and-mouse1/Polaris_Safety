#!/usr/bin/env python3
"""MH-100K Android Malware Detection Model Training"""

import os
import json
import ssl
import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, classification_report

# Disable SSL verification for HuggingFace download (if needed)
ssl._create_default_https_context = ssl._create_unverified_context

# Force using requests due to SSL issues with huggingface_hub
USE_HF_HUB = False
import requests

# Constants
DATASET_REPO = "hendriow/mh100k"
MODEL_OUTPUT_DIR = "../models"
MODEL_OUTPUT = os.path.join(MODEL_OUTPUT_DIR, "malware_detector.json")

def download_dataset():
    """Download MH-100K dataset from HuggingFace"""
    print("Downloading MH-100K dataset...")
    
    if USE_HF_HUB:
        labels_path = hf_hub_download(repo_id=DATASET_REPO, filename="mh100-labels.csv")
        features_path = hf_hub_download(repo_id=DATASET_REPO, filename="mh100.parquet")
        feature_names_path = hf_hub_download(repo_id=DATASET_REPO, filename="feature_names.csv")
    else:
        # Fallback to direct download with requests
        base_url = f"https://huggingface.co/datasets/{DATASET_REPO}/resolve/main"
        os.makedirs("temp_data", exist_ok=True)
        
        labels_path = "temp_data/mh100-labels.csv"
        features_path = "temp_data/mh100.parquet"
        feature_names_path = "temp_data/feature_names.csv"
        
        for url, path in [
            (f"{base_url}/mh100-labels.csv", labels_path),
            (f"{base_url}/mh100.parquet", features_path),
            (f"{base_url}/feature_names.csv", feature_names_path)
        ]:
            if not os.path.exists(path):
                print(f"Downloading {path}...")
                r = requests.get(url, verify=False)
                with open(path, 'wb') as f:
                    f.write(r.content)
    
    labels = pd.read_csv(labels_path)
    features = pd.read_parquet(features_path)
    all_feature_names = pd.read_csv(feature_names_path)['features'].tolist()
    
    # Use only first 24 features (permissions and basic API calls)
    # to match MlScanner.java which expects 24 features
    N_FEATURES = 24
    features = features.iloc[:, :N_FEATURES]
    feature_names = all_feature_names[:N_FEATURES]
    
    print(f"Loaded {len(features)} samples, {len(feature_names)} features")
    return features, labels, feature_names

def train_model(X_train, y_train):
    """Train logistic regression model"""
    print("Training Logistic Regression model...")
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    
    model = LogisticRegression(
        max_iter=1000,
        class_weight='balanced',
        random_state=42,
        C=1.0
    )
    model.fit(X_train_scaled, y_train)
    
    return model, scaler

def evaluate_model(model, scaler, X_test, y_test):
    """Evaluate model performance"""
    X_test_scaled = scaler.transform(X_test)
    y_pred = model.predict(X_test_scaled)
    
    accuracy = accuracy_score(y_test, y_pred)
    print(f"\nModel Accuracy: {accuracy:.4f}")
    print("\nClassification Report:")
    print(classification_report(y_test, y_pred, target_names=['Benign', 'Malware']))
    
    return accuracy

def export_model(model, scaler, feature_names, accuracy):
    """Export model to JSON format"""
    print("\nExporting model...")
    
    model_data = {
        "type": "logistic_regression",
        "coef": model.coef_.tolist(),
        "intercept": model.intercept_.tolist(),
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "feature_names": feature_names,
        "threshold": 0.5,
        "accuracy": accuracy
    }
    
    os.makedirs(MODEL_OUTPUT_DIR, exist_ok=True)
    with open(MODEL_OUTPUT, 'w') as f:
        json.dump(model_data, f, indent=2)
    
    print(f"Model saved to {MODEL_OUTPUT}")
    return model_data

def main():
    """Main training pipeline"""
    # Download data
    features, labels, feature_names = download_dataset()
    
    # Prepare data
    X = features.values
    y = labels['class'].values
    
    # Split
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )
    
    # Train
    model, scaler = train_model(X_train, y_train)
    
    # Evaluate
    accuracy = evaluate_model(model, scaler, X_test, y_test)
    
    # Export
    model_data = export_model(model, scaler, feature_names, accuracy)
    
    print("\nTraining complete!")
    return model_data

if __name__ == "__main__":
    main()
