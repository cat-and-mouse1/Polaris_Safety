# ML Malware Detection Pipeline

## Overview
This module trains an on-device ML model for Android malware detection using the MH-100K dataset.

## Training Pipeline

### Prerequisites
- Python 3.8+
- pip

### Setup
```bash
cd ml/train
pip install -r requirements.txt
```

### Train Model
```bash
python train.py
```

This will:
1. Download MH-100K dataset from HuggingFace
2. Train a Logistic Regression model
3. Export to TFLite format
4. Save to `../models/`

### Export to Android
```bash
cp ../models/malware_detector.tflite ../../app/src/main/assets/
cp ../models/ml_model_metadata.json ../../app/src/main/assets/
```

## Model Details

- **Algorithm:** Logistic Regression
- **Features:** 24 (permissions + API calls)
- **Training samples:** 100K+ Android apps
- **Accuracy:** ~87%
- **Model size:** ~10 KB

## Android Integration

The model is loaded by `MlScanner.java` and integrated into:
- `MalwareScanner.java` (app-level detection)
- `FileScanner.java` (file-level detection)

ML scores are weighted at 30% to complement existing heuristics.

## Model Updates

Models are automatically updated via `IocRefreshWorker` every 24 hours.
