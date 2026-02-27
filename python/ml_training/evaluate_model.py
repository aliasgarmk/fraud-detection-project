"""
Detailed model evaluation and testing.
"""

import os
import pandas as pd
import numpy as np
import joblib
from sklearn.model_selection import train_test_split

# Resolve paths relative to this file so the script works from any directory.
_PYTHON_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_DATA_PATH = os.path.join(_PYTHON_DIR, 'data', 'transactions.csv')
_MODEL_PATH = os.path.join(_PYTHON_DIR, 'models', 'fraud_model.pkl')
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score,
    f1_score, roc_auc_score
)


def load_model(filepath=_MODEL_PATH):
    """Load the trained model."""
    print("Loading model...")
    model_package = joblib.load(filepath)
    return model_package['model'], model_package['label_encoders']


def load_test_data():
    """Load and prepare test data."""
    df = pd.read_csv(_DATA_PATH)

    # Split again with same random seed to get the same test set
    _, test_df = train_test_split(df, test_size=0.2, random_state=42)

    return test_df


def prepare_features(df, label_encoders):
    """Prepare features the same way as training."""
    X = df.drop('is_fraud', axis=1).copy()
    y = df['is_fraud']

    # Apply same encoding
    for column, le in label_encoders.items():
        X[column] = le.transform(X[column])

    return X, y


def calculate_metrics(model, X_test, y_test):
    """Calculate all performance metrics."""
    y_pred = model.predict(X_test)
    y_proba = model.predict_proba(X_test)[:, 1]  # Probability of fraud

    metrics = {
        'accuracy':  accuracy_score(y_test, y_pred),
        'precision': precision_score(y_test, y_pred),
        'recall':    recall_score(y_test, y_pred),
        'f1':        f1_score(y_test, y_pred),
        'roc_auc':   roc_auc_score(y_test, y_proba)
    }

    return metrics, y_pred, y_proba


def test_sample_transactions(model, label_encoders):
    """Test model on hand-crafted examples."""
    print("\nTesting sample transactions:")
    print("="*50)

    samples = pd.DataFrame([
        # Legitimate: small grocery purchase during the day
        {
            'amount': 45.0, 'merchant_category': 'grocery', 'location': 'CA',
            'hour_of_day': 14, 'user_id': 1500, 'tx_velocity': 2.0,
            'amount_percentile': 0.5, 'hour_sin': 0.5, 'hour_cos': 0.8
        },
        # Fraud: large online purchase at 3am
        {
            'amount': 850.0, 'merchant_category': 'online', 'location': 'NY',
            'hour_of_day': 3, 'user_id': 1500, 'tx_velocity': 8.0,
            'amount_percentile': 0.95, 'hour_sin': -0.9, 'hour_cos': 0.4
        },
    ])

    # Encode categories
    for col, le in label_encoders.items():
        samples[col] = le.transform(samples[col])

    predictions = model.predict(samples)
    probabilities = model.predict_proba(samples)[:, 1]

    descriptions = [
        "Small grocery purchase at 2pm (should be LEGITIMATE)",
        "Large online purchase at 3am (should be FRAUD)",
    ]

    for desc, pred, prob in zip(descriptions, predictions, probabilities):
        result = "FRAUD" if pred == 1 else "LEGITIMATE"
        print(f"  {desc}")
        print(f"    -> {result} (fraud probability: {prob:.2%})\n")


def print_summary(metrics):
    """Print performance summary."""
    print("\n" + "="*50)
    print("MODEL PERFORMANCE SUMMARY")
    print("="*50)
    print(f"Accuracy:  {metrics['accuracy']:.2%}  (Overall correctness)")
    print(f"Precision: {metrics['precision']:.2%}  (Of flagged fraud, how many are real?)")
    print(f"Recall:    {metrics['recall']:.2%}  (Of real fraud, how many did we catch?)")
    print(f"F1-Score:  {metrics['f1']:.2%}  (Balance of precision and recall)")
    print(f"ROC-AUC:   {metrics['roc_auc']:.2%}  (Overall discrimination ability)")
    print("="*50)

    print("\nInterpretation:")
    if metrics['accuracy'] > 0.95:
        print("[OK] Excellent accuracy - model performs very well")
    elif metrics['accuracy'] > 0.90:
        print("[OK] Good accuracy - model is production-ready")
    else:
        print("[!] Accuracy could be improved")

    if metrics['precision'] > 0.90:
        print("[OK] High precision - few false alarms")
    else:
        print("[!] Lower precision - some legitimate transactions may be flagged")

    if metrics['recall'] > 0.85:
        print("[OK] Good recall - catching most fraud")
    else:
        print("[!] Lower recall - some fraud slipping through")


if __name__ == "__main__":
    # Load model
    model, label_encoders = load_model()

    # Load test data
    test_df = load_test_data()
    X_test, y_test = prepare_features(test_df, label_encoders)

    # Calculate metrics
    metrics, y_pred, y_proba = calculate_metrics(model, X_test, y_test)

    # Print results
    print_summary(metrics)

    # Test hand-crafted samples
    test_sample_transactions(model, label_encoders)

    print("[OK] Evaluation complete!")
    print("\nNext steps:")
    print("  1. Review the metrics above")
    print("  2. If accuracy >95%, proceed to Phase 2 (FastAPI)")
    print("  3. If accuracy <95%, retrain with more data or tune parameters")
