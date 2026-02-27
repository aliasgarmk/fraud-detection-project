"""
Train fraud detection model using Random Forest.

This is where the magic happens - the model learns patterns from data!
"""

import os
import pandas as pd

# Resolve paths relative to this file so the script works from any directory.
_PYTHON_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_DATA_PATH = os.path.join(_PYTHON_DIR, 'data', 'transactions.csv')
_MODEL_PATH = os.path.join(_PYTHON_DIR, 'models', 'fraud_model.pkl')
_MODELS_DIR = os.path.join(_PYTHON_DIR, 'models')
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import LabelEncoder
from sklearn.metrics import classification_report, confusion_matrix
import joblib


def load_data(filepath=_DATA_PATH):
    """Load the generated dataset."""
    print("Loading dataset...")
    df = pd.read_csv(filepath)
    print(f"Loaded {len(df)} transactions")
    return df


def preprocess_data(df):
    """
    Prepare data for ML model.
    This is like data validation + transformation in Java.
    """
    print("\nPreprocessing data...")

    # Separate features (X) and target (y)
    # X = input features, y = what we're predicting (fraud or not)
    X = df.drop('is_fraud', axis=1)
    y = df['is_fraud']

    # Encode categorical variables
    # merchant_category: 'grocery' -> 0, 'restaurant' -> 1, etc.
    # This is like enum.ordinal() in Java
    label_encoders = {}

    for column in ['merchant_category', 'location']:
        le = LabelEncoder()
        X = X.copy()
        X[column] = le.fit_transform(X[column])
        label_encoders[column] = le
        print(f"  Encoded {column}: {len(le.classes_)} categories")

    print(f"\nFeatures shape: {X.shape}")
    print(f"Feature columns: {list(X.columns)}")

    return X, y, label_encoders


def split_data(X, y, test_size=0.2):
    """
    Split data into training and testing sets.
    Like having separate test data in JUnit.

    80% for training, 20% for testing (unseen data)
    """
    X_train, X_test, y_train, y_test = train_test_split(
        X, y,
        test_size=test_size,
        random_state=42,
        stratify=y  # Ensure same fraud ratio in train/test
    )

    print(f"\nData split:")
    print(f"  Training: {len(X_train)} samples")
    print(f"  Testing:  {len(X_test)} samples")
    print(f"  Train fraud rate: {y_train.mean():.2%}")
    print(f"  Test fraud rate:  {y_test.mean():.2%}")

    return X_train, X_test, y_train, y_test


def train_model(X_train, y_train):
    """
    Train Random Forest classifier.

    Random Forest = ensemble of decision trees
    Think of it as having multiple experts vote on each prediction
    """
    print("\nTraining Random Forest model...")
    print("This may take 30-60 seconds...")

    model = RandomForestClassifier(
        n_estimators=100,      # Number of trees (more = better, but slower)
        max_depth=10,          # How deep each tree can grow
        min_samples_split=20,  # Minimum samples to split a node
        min_samples_leaf=10,   # Minimum samples in leaf node
        random_state=42,       # Reproducibility
        n_jobs=-1              # Use all CPU cores
    )

    # Train the model (this is where learning happens!)
    model.fit(X_train, y_train)

    print("[OK] Model training complete!")

    return model


def evaluate_model(model, X_test, y_test):
    """
    Test the model on unseen data.
    This is like running JUnit tests.
    """
    print("\nEvaluating model on test data...")

    # Make predictions
    y_pred = model.predict(X_test)

    # Calculate accuracy
    accuracy = (y_pred == y_test).mean()
    print(f"\nAccuracy: {accuracy:.2%}")

    # Detailed metrics
    print("\nClassification Report:")
    print("="*50)
    print(classification_report(y_test, y_pred,
                                target_names=['Legitimate', 'Fraud']))

    # Confusion matrix
    print("\nConfusion Matrix:")
    print("="*50)
    cm = confusion_matrix(y_test, y_pred)
    print(f"True Negatives  (correctly identified legitimate): {cm[0, 0]}")
    print(f"False Positives (legitimate flagged as fraud):     {cm[0, 1]}")
    print(f"False Negatives (fraud missed):                    {cm[1, 0]}")
    print(f"True Positives  (correctly identified fraud):      {cm[1, 1]}")

    # Feature importance
    print("\nTop 5 Important Features:")
    print("="*50)
    feature_importance = pd.DataFrame({
        'feature': X_test.columns,
        'importance': model.feature_importances_
    }).sort_values('importance', ascending=False)
    print(feature_importance.head(5).to_string(index=False))

    return accuracy


def save_model(model, label_encoders, filepath=_MODEL_PATH):
    """
    Save trained model to disk.
    Like serializing a Java object.
    """
    os.makedirs(_MODELS_DIR, exist_ok=True)

    # Save model and encoders together
    model_package = {
        'model': model,
        'label_encoders': label_encoders
    }

    joblib.dump(model_package, filepath)
    print(f"\nModel saved to: {filepath}")

    size_mb = os.path.getsize(filepath) / (1024 * 1024)
    print(f"Model size: {size_mb:.2f} MB")


if __name__ == "__main__":
    # 1. Load data
    df = load_data()

    # 2. Preprocess
    X, y, label_encoders = preprocess_data(df)

    # 3. Split into train/test
    X_train, X_test, y_train, y_test = split_data(X, y)

    # 4. Train model
    model = train_model(X_train, y_train)

    # 5. Evaluate
    accuracy = evaluate_model(model, X_test, y_test)

    # 6. Save model only if accuracy meets the threshold
    if accuracy > 0.90:
        save_model(model, label_encoders)
        print("\n" + "="*50)
        print("[OK] SUCCESS! Model trained and saved.")
        print("="*50)
        print("Next step: Run evaluate_model.py for detailed analysis")
    else:
        print(f"\n⚠ Warning: Accuracy {accuracy:.2%} is below 90%")
        print("Consider generating more data or tuning hyperparameters")
