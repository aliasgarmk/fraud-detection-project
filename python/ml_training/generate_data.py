""" Generate synthetic transaction data for fraud detection.  This script creates realistic transaction data with fraud patterns. Similar to creating test fixtures in Java, but for ML training. """
import os
import pandas as pd
import numpy as np
from datetime import datetime, timedelta
import random

# Resolve paths relative to this file so the script works from any directory.
# ml_training/ lives inside python/, so parent of parent is the project root's python/.
_PYTHON_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_DATA_PATH = os.path.join(_PYTHON_DIR, 'data', 'transactions.csv')

# Set random seed for reproducibility (like @Before in JUnit)
np.random.seed(42)
random.seed(42)
def generate_transactions(n_samples=10000, fraud_ratio=0.1):     
    """ Generate synthetic transaction dataset.Args: n_samples: Total number of transactions to generate fraud_ratio: Percentage of fraudulent transactions (0.1 = 10%) Returns: pandas DataFrame with transaction data     """     
    n_fraud = int(n_samples * fraud_ratio)
    n_legitimate = n_samples - n_fraud
    print(f"Generating {n_samples} transactions...")
    print(f"  - Legitimate: {n_legitimate}")
    print(f"  - Fraudulent: {n_fraud}")
    
    # Generate legitimate transactions 
    legitimate_transactions = generate_legitimate(n_legitimate)
    
    # Generate fraudulent transactions
    fraudulent_transactions = generate_fraudulent(n_fraud)
    
    #Combine and shuffle
    all_transactions = pd.concat([legitimate_transactions, fraudulent_transactions])     
    all_transactions = all_transactions.sample(frac=1).reset_index(drop=True)          
    print(f"\nDataset created: {len(all_transactions)} transactions")     
    print(f"Fraud rate: {all_transactions['is_fraud'].mean():.2%}")          
    
    return all_transactions  

def generate_legitimate(n):        
    """Generate legitimate transaction patterns."""          
    # Transaction amounts - most legitimate transactions are small     
    # Normal distribution centered around $50, std dev $30     
    
    amounts = np.random.normal(50, 30, n)     
    amounts = np.clip(amounts, 5, 500)  # Keep between $5-$500
    
    # Merchant categories (weighted toward common categories)   
    
    categories = np.random.choice(
        ['grocery', 'restaurant', 'gas_station', 'retail', 'online'],
        size=n,
        p=[0.3, 0.25, 0.2, 0.15, 0.1] # Probabilities
    )          
    
    # Locations (US states - mostly local)
    locations = np.random.choice(
        ['CA', 'NY', 'TX', 'FL', 'IL'],
        size=n,
        p=[0.4, 0.25, 0.15, 0.12, 0.08]
    )
    
    # Transaction times (mostly business hours
    hours = np.random.choice(range(24), size=n, p=get_hour_distribution())
    
    # User IDs (1000 unique users)
    user_ids = np.random.choice(range(1000, 2000), size=n)
    
    return pd.DataFrame({
        'amount': amounts,
        'merchant_category': categories,
        'location': locations,
        'hour_of_day': hours,
        'user_id': user_ids,
        'is_fraud': 0  # Legitimate
    })

def generate_fraudulent(n):
    """Generate fraudulent transaction patterns."""
    
    # Fraudulent transactions tend to be larger
    amounts = np.random.normal(200, 100, n)
    amounts = np.clip(amounts, 100, 1000)  # $100-$1000

    # Fraud often happens in online/retail
    categories = np.random.choice(
        ['online', 'retail', 'gas_station', 'restaurant', 'grocery'],         
        size=n,         
        p=[0.4, 0.3, 0.15, 0.1, 0.05]     
    )

    # More diverse locations (stolen cards used far from home)     
    locations = np.random.choice(
        ['CA', 'NY', 'TX', 'FL', 'IL', 'WA', 'AZ', 'NV'],
        size=n
    )

    # Fraud happens at unusual hours
    hours = np.random.choice(
        range(24),
        size=n,
        p=get_fraud_hour_distribution()
    )

    # Same user pool (fraudsters target existing users)
    user_ids = np.random.choice(range(1000, 2000), size=n)
    
    return pd.DataFrame({         
        'amount': amounts,         
        'merchant_category': categories,         
        'location': locations,         
        'hour_of_day': hours,         
        'user_id': user_ids,         
        'is_fraud': 1  # Fraudulent     
        })  

def get_hour_distribution():     
    """Legitimate transactions peak during business hours."""     
    # Hour probabilities (0-23)     
    probs = np.array([
        0.01, 0.01, 0.01, 0.01, 0.02, 0.03,  # 0-5am: very low         
        0.04, 0.05, 0.06, 0.07, 0.07, 0.08,  # 6-11am: rising         
        0.09, 0.08, 0.07, 0.07, 0.06, 0.06,  # 12-5pm: peak         
        0.05, 0.04, 0.03, 0.02, 0.02, 0.01   # 6-11pm: declining     
        ])
   
    return probs / probs.sum()  

def get_fraud_hour_distribution():     
    """Fraud peaks at night when victims are asleep."""     
    probs = np.array([         
        0.08, 0.09, 0.10, 0.08, 0.06, 0.04,  # 0-5am: high         
        0.02, 0.02, 0.02, 0.03, 0.03, 0.04,  # 6-11am: low         
        0.04, 0.04, 0.04, 0.04, 0.04, 0.04,  # 12-5pm: medium         
        0.05, 0.06, 0.07, 0.08, 0.09, 0.08   # 6-11pm: rising     
        ])     
    return probs / probs.sum()  

def add_engineered_features(df):     
    """     Add derived features to improve model performance.     
    Similar to creating computed fields in Java.     """     
    print("\nEngineering features...")          
    
    # 1. Transaction velocity (transactions per user per hour)     
    df['tx_velocity'] = df.groupby('user_id')['user_id'].transform('count') / 24          
    
    # 2. Amount percentile for user (is this unusually high?)     
    df['amount_percentile'] = df.groupby('user_id')['amount'].rank(pct=True)          
    
    # 3. Hour category (cyclical encoding)     
    df['hour_sin'] = np.sin(2 * np.pi * df['hour_of_day'] / 24)     
    df['hour_cos'] = np.cos(2 * np.pi * df['hour_of_day'] / 24)          
    print(f"Added {4} engineered features")          
    return df  

def save_dataset(df, filepath=_DATA_PATH):
    """Save dataset to CSV file."""
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    df.to_csv(filepath, index=False)
    print(f"\nDataset saved to: {filepath}")
    print(f"File size: {len(df)} rows x {len(df.columns)} columns")


if __name__ == "__main__":
    # Generate dataset
    df = generate_transactions(n_samples=10000, fraud_ratio=0.1)

    # Add engineered features
    df = add_engineered_features(df)

    # Display sample
    print("\n" + "="*50)
    print("Sample transactions:")
    print("="*50)
    print(df.head(10))

    print("\n" + "="*50)
    print("Dataset statistics:")
    print("="*50)
    print(df.describe())

    # Save to file
    save_dataset(df)
    print("\n[OK] Data generation complete!")
    print("Next step: Run train_model.py")