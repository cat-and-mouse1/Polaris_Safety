#!/usr/bin/env python3
"""
Train LR model on MH-100K - Optimized version.
Uses smaller sample for faster training.
"""

import pandas as pd
import numpy as np
from pathlib import Path
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
from sklearn.model_selection import train_test_split
import json
import time

# Directories
data_dir = Path(__file__).parent.parent / "data" / "raw"
assets_dir = Path(__file__).parent.parent.parent / "app" / "src" / "main" / "assets"

print("=" * 60)
print("MH-100K LR Training (Optimized)")
print("=" * 60)

start_time = time.time()

# Load only 20000 rows for faster training
csv_file = data_dir / "mh100.csv"
print(f"\nLoading 20,000 rows from {csv_file}...")

df = pd.read_csv(csv_file, nrows=20000)
print(f"Loaded: {df.shape}")

# Find label column
label_col = None
for col in df.columns:
    if 'label' in col.lower():
        label_col = col
        break

if label_col is None:
    label_col = df.columns[-1]

print(f"Label: {label_col}")

# Get feature columns
metadata_cols = ['sha256', 'filename', 'package', 'apk_size', 'compile_api']
feature_cols = [col for col in df.columns if col not in metadata_cols and col != label_col]

print(f"Features: {len(feature_cols)}")

# Prepare data
X = df[feature_cols].fillna(0).values
y = df[label_col].values

# Convert to binary
unique_labels = np.unique(y)
print(f"Labels: {unique_labels}")

if len(unique_labels) > 2:
    y = (y == unique_labels[1]).astype(int)

print(f"Distribution: {np.sum(y == 0)} benign, {np.sum(y == 1)} malware")

# Split
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42, stratify=y
)

print(f"Train: {len(X_train)}, Test: {len(X_test)}")

# Scale
scaler = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled = scaler.transform(X_test)

# Train
print("\nTraining...")
model = LogisticRegression(
    max_iter=500,
    random_state=42,
    class_weight='balanced'
)
model.fit(X_train_scaled, y_train)

# Evaluate
y_pred = model.predict(X_test_scaled)

accuracy = accuracy_score(y_test, y_pred)
precision = precision_score(y_test, y_pred, zero_division=0)
recall = recall_score(y_test, y_pred, zero_division=0)
f1 = f1_score(y_test, y_pred, zero_division=0)

print(f"\nResults:")
print(f"  Accuracy:  {accuracy:.4f}")
print(f"  Precision: {precision:.4f}")
print(f"  Recall:    {recall:.4f}")
print(f"  F1 Score:  {f1:.4f}")

# Export
n_features = min(43, len(feature_cols))

model_data = {
    "type": "logistic_regression",
    "coef": model.coef_[:, :n_features].tolist(),
    "intercept": model.intercept_.tolist(),
    "scaler_mean": scaler.mean_[:n_features].tolist(),
    "scaler_scale": scaler.scale_[:n_features].tolist(),
    "feature_names": feature_cols[:n_features],
    "n_features": n_features,
    "threshold": 0.5,
    "accuracy": accuracy,
    "f1_score": f1,
    "precision": precision,
    "recall": recall
}

model_file = assets_dir / "malware_detector_v2.json"
with open(model_file, 'w') as f:
    json.dump(model_data, f, indent=2)

metadata = {
    "version": "2.0.0",
    "model_file": "malware_detector_v2.json",
    "type": "logistic_regression",
    "features": n_features,
    "threshold": 0.5,
    "accuracy": accuracy,
    "f1_score": f1,
    "precision": precision,
    "recall": recall,
    "trained_on": "MH-100K",
    "training_date": "2026-08-31",
    "feature_names": feature_cols[:n_features],
    "total_samples": len(X),
    "train_samples": len(X_train),
    "test_samples": len(X_test)
}

metadata_file = assets_dir / "malware_detector_v2_metadata.json"
with open(metadata_file, 'w') as f:
    json.dump(metadata, f, indent=2)

elapsed = time.time() - start_time

print(f"\nModel: {model_file}")
print(f"Time: {elapsed:.1f}s ({elapsed/60:.1f}min)")
print("\nDone!")
