#!/usr/bin/env python3
"""
Train Logistic Regression model on MH-100K dataset (chunk processing).
"""

import pandas as pd
import numpy as np
from pathlib import Path
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
import json
import time

# Directories
data_dir = Path(__file__).parent.parent / "data" / "raw"
assets_dir = Path(__file__).parent.parent.parent / "app" / "src" / "main" / "assets"

print("=" * 60)
print("MH-100K Logistic Regression Training")
print("=" * 60)

start_time = time.time()

# Step 1: Load first chunk to get column info
csv_file = data_dir / "mh100.csv"
print(f"\n[1/5] Loading column info from {csv_file}...")

# Read only headers and first few rows
df_sample = pd.read_csv(csv_file, nrows=10)
print(f"  Columns: {len(df_sample.columns)}")
print(f"  First 10 columns: {list(df_sample.columns[:10])}")

# Find label column
label_col = None
for col in df_sample.columns:
    if 'label' in col.lower() or 'class' in col.lower() or 'malware' in col.lower():
        label_col = col
        break

if label_col is None:
    # Check if last column is label
    last_col = df_sample.columns[-1]
    unique_vals = df_sample[last_col].unique()
    if len(unique_vals) <= 10:
        label_col = last_col

print(f"  Label column: {label_col}")

# Metadata columns to exclude
metadata_cols = ['sha256', 'filename', 'package', 'apk_size', 'compile_api', 'label']
feature_cols = [col for col in df_sample.columns if col not in metadata_cols]
print(f"  Feature columns: {len(feature_cols)}")

# Step 2: Initialize scaler and model
print(f"\n[2/5] Initializing model...")

# We'll use incremental learning
scaler = StandardScaler()
model = LogisticRegression(
    max_iter=1000,
    random_state=42,
    class_weight='balanced',
    warm_start=True  # Allow incremental learning
)

# Step 3: Process data in chunks
print(f"\n[3/5] Processing data in chunks...")

chunk_size = 5000
max_chunks = 20  # Process 100K rows max
total_rows = 0
X_all = []
y_all = []

for i, chunk in enumerate(pd.read_csv(csv_file, chunksize=chunk_size, nrows=chunk_size * max_chunks)):
    # Extract features and labels
    if label_col and label_col in chunk.columns:
        y_chunk = chunk[label_col].values
        X_chunk = chunk[feature_cols].fillna(0).values
    else:
        # Assume last column is label
        y_chunk = chunk.iloc[:, -1].values
        X_chunk = chunk.iloc[:, :-1].fillna(0).values
    
    X_all.append(X_chunk)
    y_all.append(y_chunk)
    total_rows += len(chunk)
    
    print(f"  Chunk {i+1}: {len(chunk)} rows (total: {total_rows})")
    
    if i >= max_chunks - 1:
        break

# Combine all chunks
X = np.vstack(X_all)
y = np.concatenate(y_all)

print(f"\n  Final dataset: {X.shape[0]} rows, {X.shape[1]} features")

# Convert labels to binary
unique_labels = np.unique(y)
print(f"  Unique labels: {unique_labels}")

if len(unique_labels) > 2:
    # Multi-class to binary
    y = (y == unique_labels[1]).astype(int)
    print(f"  Converted to binary: {np.sum(y == 0)} benign, {np.sum(y == 1)} malware")
else:
    print(f"  Label distribution: {np.sum(y == 0)} benign, {np.sum(y == 1)} malware")

# Step 4: Train model
print(f"\n[4/5] Training Logistic Regression...")

# Split data
from sklearn.model_selection import train_test_split
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42, stratify=y
)

print(f"  Train: {len(X_train)} samples")
print(f"  Test: {len(X_test)} samples")

# Fit scaler
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled = scaler.transform(X_test)

# Train model
model.fit(X_train_scaled, y_train)

# Step 5: Evaluate
print(f"\n[5/5] Evaluating model...")

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

# Get top features
feature_importance = np.abs(model.coef_[0])
top_indices = np.argsort(feature_importance)[-20:][::-1]

print(f"\nTop 20 features:")
for i, idx in enumerate(top_indices):
    if idx < len(feature_cols):
        print(f"  {i+1}. {feature_cols[idx]}: {feature_importance[idx]:.4f}")

# Export model
print(f"\nExporting model...")

# Limit to 43 features for Android compatibility
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

# Save model
model_file = assets_dir / "malware_detector_v2.json"
with open(model_file, 'w') as f:
    json.dump(model_data, f, indent=2)

# Save metadata
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

elapsed_time = time.time() - start_time

print(f"\nModel saved to: {model_file}")
print(f"Metadata saved to: {metadata_file}")
print(f"\nTraining time: {elapsed_time:.1f} seconds ({elapsed_time/60:.1f} minutes)")

print("\n" + "=" * 60)
print("Training Complete!")
print("=" * 60)
