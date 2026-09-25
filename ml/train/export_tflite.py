#!/usr/bin/env python3
"""Export trained model to TFLite format"""

import json
import numpy as np
import tensorflow as tf

def convert_json_to_tflite(json_path, tflite_path):
    """Convert JSON model to TFLite"""
    with open(json_path, 'r') as f:
        model_data = json.load(f)
    
    coef = np.array(model_data['coef'], dtype=np.float32)
    intercept = np.array(model_data['intercept'], dtype=np.float32)
    scaler_mean = np.array(model_data['scaler_mean'], dtype=np.float32)
    scaler_scale = np.array(model_data['scaler_scale'], dtype=np.float32)
    
    # Create TFLite model using Keras
    n_features = len(model_data['feature_names'])
    
    # Build model: sigmoid(coef @ (x - mean) / scale + intercept)
    model = tf.keras.Sequential([
        tf.keras.layers.InputLayer(input_shape=(n_features,)),
        tf.keras.layers.Lambda(lambda x: (x - scaler_mean) / scaler_scale),
        tf.keras.layers.Dense(1, activation='sigmoid', 
                            weights=[coef.T, intercept])
    ])
    
    # Convert to TFLite
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    
    # Save
    with open(tflite_path, 'wb') as f:
        f.write(tflite_model)
    
    print(f"TFLite model saved to {tflite_path}")
    print(f"Model size: {len(tflite_model) / 1024:.1f} KB")
    
    return tflite_path

if __name__ == "__main__":
    convert_json_to_tflite(
        "../models/malware_detector.json",
        "../models/malware_detector.tflite"
    )
