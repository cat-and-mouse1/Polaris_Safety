#!/usr/bin/env python3
"""
Process MH-100K dataset and create training data.
"""

import pandas as pd
import numpy as np
from pathlib import Path
from sklearn.model_selection import train_test_split
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
from sklearn.pipeline import Pipeline
import json

# Output directory
output_dir = Path(__file__).parent.parent / "data" / "processed"
output_dir.mkdir(parents=True, exist_ok=True)

# Assets directory
assets_dir = Path(__file__).parent.parent.parent / "app" / "src" / "main" / "assets"

print("=" * 60)
print("Processing MH-100K Dataset")
print("=" * 60)

# Load CSV in chunks
csv_file = Path(__file__).parent.parent / "data" / "raw" / "mh100.csv"

print(f"\nLoading {csv_file}...")
print("This may take a while due to file size (5GB)...")

# Read only first 10000 rows for initial processing
chunk_size = 10000
df_list = []

try:
    for i, chunk in enumerate(pd.read_csv(csv_file, chunksize=chunk_size, nrows=50000)):
        df_list.append(chunk)
        print(f"  Loaded chunk {i+1}: {len(chunk)} rows")
        if i >= 4:  # Only load 5 chunks (50000 rows)
            break
    
    df = pd.concat(df_list, ignore_index=True)
    print(f"\nTotal rows loaded: {len(df)}")
    
except Exception as e:
    print(f"Error loading CSV: {e}")
    exit(1)

# Check columns
print(f"\nColumns: {list(df.columns[:20])}...")
print(f"Total columns: {len(df.columns)}")

# Find label column
label_col = None
for col in df.columns:
    if 'label' in col.lower() or 'class' in col.lower() or 'malware' in col.lower():
        label_col = col
        print(f"Found label column: {col}")
        break

if label_col is None:
    print("No label column found. Using last column as label.")
    label_col = df.columns[-1]

# Get feature columns (exclude metadata)
metadata_cols = ['sha256', 'filename', 'package', 'apk_size', 'compile_api']
feature_cols = [col for col in df.columns if col not in metadata_cols and col != label_col]

print(f"\nFeature columns: {len(feature_cols)}")
print(f"Label column: {label_col}")

# Sample data for training (too large to process all)
sample_size = min(20000, len(df))
print(f"\nSampling {sample_size} rows for training...")

df_sample = df.sample(n=sample_size, random_state=42)

# Prepare features and labels
X = df_sample[feature_cols].fillna(0).values
y = df_sample[label_col].values

# Convert labels to binary (0/1)
if y.dtype == object:
    unique_labels = np.unique(y)
    print(f"Unique labels: {unique_labels}")
    label_map = {label: i for i, label in enumerate(unique_labels)}
    y = np.array([label_map[label] for label in y])
    print(f"Label mapping: {label_map}")

print(f"\nFeature matrix shape: {X.shape}")
print(f"Label distribution: {np.sum(y == 0)} benign, {np.sum(y == 1)} malware")

# Train model
print("\nTraining model...")

X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42, stratify=y
)

pipeline = Pipeline([
    ('scaler', StandardScaler()),
    ('model', LogisticRegression(
        max_iter=1000,
        random_state=42,
        class_weight='balanced'
    ))
])

pipeline.fit(X_train, y_train)

# Evaluate
y_pred = pipeline.predict(X_test)

accuracy = accuracy_score(y_test, y_pred)
precision = precision_score(y_test, y_pred, zero_division=0)
recall = recall_score(y_test, y_pred, zero_division=0)
f1 = f1_score(y_test, y_pred, zero_division=0)

print(f"\nResults:")
print(f"  Accuracy:  {accuracy:.4f}")
print(f"  Precision: {precision:.4f}")
print(f"  Recall:    {recall:.4f}")
print(f"  F1 Score:  {f1:.4f}")

# Export model
model = pipeline.named_steps['model']
scaler = pipeline.named_steps['scaler']

# Get top features
feature_importance = np.abs(model.coef_[0])
top_indices = np.argsort(feature_importance)[-20:][::-1]

print(f"\nTop 20 features:")
for i, idx in enumerate(top_indices):
    if idx < len(feature_cols):
        print(f"  {i+1}. {feature_cols[idx]}: {feature_importance[idx]:.4f}")

# Create model data (limit to 43 features for Android)
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
    "total_samples": len(df_sample),
    "train_samples": len(X_train),
    "test_samples": len(X_test)
}

metadata_file = assets_dir / "malware_detector_v2_metadata.json"
with open(metadata_file, 'w') as f:
    json.dump(metadata, f, indent=2)

print(f"\nModel saved to: {model_file}")
print(f"Metadata saved to: {metadata_file}")

print("\n" + "=" * 60)
print("Training Complete!")
print("=" * 60)
