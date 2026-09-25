#!/usr/bin/env python3
"""Test trained model on sample data"""

import json
import numpy as np
import sys
import os

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from train.train import download_dataset

def test_model():
    # Load model
    model_path = os.path.join(os.path.dirname(__file__), '..', 'models', 'malware_detector.json')
    with open(model_path, 'r') as f:
        model_data = json.load(f)
    
    # Load test data
    features, labels, feature_names = download_dataset()
    X = features.values[:100]  # Test on 100 samples
    y_true = labels['class'].values[:100]
    
    # Simulate prediction
    coef = np.array(model_data['coef'])
    intercept = model_data['intercept'][0]
    scaler_mean = np.array(model_data['scaler_mean'])
    scaler_scale = np.array(model_data['scaler_scale'])
    
    # Normalize
    X_scaled = (X - scaler_mean) / scaler_scale
    
    # Predict
    logits = X_scaled @ coef.T + intercept
    y_pred = (1 / (1 + np.exp(-logits)) > 0.5).astype(int)
    
    # Accuracy
    accuracy = np.mean(y_pred == y_true)
    print(f"Test Accuracy: {accuracy:.4f}")
    
    # Assert reasonable accuracy
    assert accuracy > 0.7, f"Accuracy too low: {accuracy}"
    print("Model test passed!")
    
    return accuracy

if __name__ == "__main__":
    test_model()
