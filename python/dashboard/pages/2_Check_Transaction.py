"""
Check Transaction — interactive fraud scoring page.
Users fill in a transaction form; the page calls POST /predict on the
fraud-api and shows a clear FRAUD / LEGITIMATE alert with details.
"""

import os
import uuid
from datetime import datetime, timezone

import requests
import streamlit as st

# ── Configuration ─────────────────────────────────────────────────────────────

FRAUD_API_URL = os.getenv("FRAUD_API_URL", "http://localhost:8000")

MERCHANT_CATEGORIES = ["grocery", "restaurant", "gas_station", "retail", "online"]
LOCATIONS = ["AZ", "CA", "FL", "IL", "NV", "NY", "TX", "WA"]

st.set_page_config(
    page_title="Check Transaction",
    page_icon="🔎",
    layout="centered",
)

# ── Page header ───────────────────────────────────────────────────────────────

st.title("🔎 Check a Transaction")
st.caption("Submit transaction details to the fraud-detection API and get an instant result.")

# ── Form ──────────────────────────────────────────────────────────────────────

with st.form("transaction_form"):
    st.subheader("Transaction Details")

    col1, col2 = st.columns(2)

    with col1:
        transaction_id = st.text_input(
            "Transaction ID",
            value=f"TXN-{uuid.uuid4().hex[:8].upper()}",
            help="Unique identifier for this transaction.",
        )
        merchant_category = st.selectbox(
            "Merchant Category",
            options=MERCHANT_CATEGORIES,
            index=0,
        )
        user_id = st.text_input(
            "User ID",
            value="user_1000",
            help="Customer / account identifier.",
        )

    with col2:
        amount = st.number_input(
            "Amount ($)",
            min_value=0.01,
            max_value=100_000.0,
            value=50.0,
            step=0.01,
            format="%.2f",
        )
        location = st.selectbox(
            "Location (State)",
            options=LOCATIONS,
            index=1,  # CA
        )
        txn_date = st.date_input(
            "Date",
            value=datetime.now(timezone.utc).date(),
            help="Date of the transaction (UTC).",
        )
        txn_time = st.time_input(
            "Time (UTC)",
            value=datetime.now(timezone.utc).time().replace(second=0, microsecond=0),
            step=60,
            help="Hour and minute of the transaction (UTC).",
        )

    submitted = st.form_submit_button("Check for Fraud", use_container_width=True, type="primary")

# ── API call & result ─────────────────────────────────────────────────────────

if submitted:
    if not transaction_id.strip():
        st.error("Transaction ID cannot be empty.")
        st.stop()

    payload = {
        "transaction_id": transaction_id.strip(),
        "amount": amount,
        "merchant_category": merchant_category,
        "location": location,
        "timestamp": datetime.combine(txn_date, txn_time).isoformat() + "Z",
        "user_id": user_id.strip(),
    }

    with st.spinner("Contacting fraud detection API…"):
        try:
            response = requests.post(
                f"{FRAUD_API_URL}/predict",
                json=payload,
                timeout=10,
            )
        except requests.exceptions.ConnectionError:
            st.error(
                f"Cannot reach the fraud API at `{FRAUD_API_URL}`. "
                "Make sure the API service is running."
            )
            st.stop()
        except requests.exceptions.Timeout:
            st.error("The fraud API did not respond within 10 seconds.")
            st.stop()

    # ── Handle API errors ─────────────────────────────────────────────────────
    if response.status_code == 409:
        st.warning(
            f"Transaction `{transaction_id}` has already been processed. "
            "Change the Transaction ID to submit a new one."
        )
        st.stop()

    if response.status_code == 422:
        detail = response.json().get("detail", response.text)
        st.error(f"Validation error from API: {detail}")
        st.stop()

    if response.status_code != 200:
        st.error(f"API returned HTTP {response.status_code}: {response.text}")
        st.stop()

    result = response.json()

    # ── Result banner ─────────────────────────────────────────────────────────
    st.divider()

    is_fraud = result["is_fraudulent"]
    fraud_score = result["fraud_score"]
    confidence = result["confidence"].capitalize()
    risk_factors = result.get("risk_factors", [])
    processing_ms = result.get("processing_time_ms", 0)

    if is_fraud:
        st.error("## FRAUD DETECTED", icon="🚨")
    else:
        st.success("## LEGITIMATE TRANSACTION", icon="✅")

    # ── Score & confidence metrics ────────────────────────────────────────────
    m1, m2, m3 = st.columns(3)
    m1.metric("Fraud Score", f"{fraud_score:.4f}")
    m2.metric("Confidence", confidence)
    m3.metric("Processing Time", f"{processing_ms:.1f} ms")

    # Fraud score bar
    bar_colour = "red" if is_fraud else "green"
    st.markdown(f"**Fraud Score** — {fraud_score:.1%}")
    st.progress(fraud_score)

    # ── Risk factors ──────────────────────────────────────────────────────────
    if risk_factors:
        st.subheader("Risk Factors")
        for factor in risk_factors:
            st.markdown(f"- `{factor}`")
    else:
        st.info("No specific risk factors flagged.")

    # ── Raw payload (collapsible) ─────────────────────────────────────────────
    with st.expander("Request payload sent to API"):
        st.json(payload)

    with st.expander("Full API response"):
        st.json(result)
