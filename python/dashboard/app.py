"""
Fraud Detection Dashboard — Phase 4
Real-time monitoring of the fraud-detection API using Streamlit + Plotly.
Reads directly from the PostgreSQL fraud_db that the Python API writes to.
"""

import os

import pandas as pd
import plotly.express as px
import psycopg2
import streamlit as st

# ── Configuration ─────────────────────────────────────────────────────────────

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://fraud_user:changeme@localhost:5432/fraud_db",
)

st.set_page_config(
    page_title="Fraud Detection Dashboard",
    page_icon="🔍",
    layout="wide",
)

# ── Data loading ──────────────────────────────────────────────────────────────


@st.cache_resource
def _get_conn():
    return psycopg2.connect(DATABASE_URL)


@st.cache_data(ttl=30)
def load_transactions() -> pd.DataFrame:
    """Load all transactions from the API database. Cached for 30 s."""
    conn = _get_conn()
    query = """
        SELECT
            transaction_id,
            user_id,
            amount,
            merchant_category,
            location,
            fraud_score,
            is_fraudulent,
            confidence,
            created_at
        FROM transactions
        ORDER BY created_at DESC
    """
    df = pd.read_sql(query, conn, parse_dates=["created_at"])
    return df


# ── Page ─────────────────────────────────────────────────────────────────────

st.title("🔍 Fraud Detection Dashboard")
st.caption("Auto-refreshes every 30 seconds. Data sourced from the live fraud_db.")

# Attempt DB connection — show a friendly error if postgres isn't reachable yet
try:
    df = load_transactions()
except Exception as exc:
    st.error(f"Could not connect to the database: {exc}")
    st.info("Make sure the postgres service is running and DATABASE_URL is set correctly.")
    st.stop()

if df.empty:
    st.warning("No transactions in the database yet. Send some requests to the API first.")
    st.stop()

# ── KPI cards ─────────────────────────────────────────────────────────────────

total = len(df)
fraud_count = int(df["is_fraudulent"].sum())
fraud_rate = fraud_count / total * 100 if total else 0
avg_score = df["fraud_score"].mean()

col1, col2, col3, col4 = st.columns(4)
col1.metric("Total Transactions", f"{total:,}")
col2.metric("Fraudulent", f"{fraud_count:,}")
col3.metric("Fraud Rate", f"{fraud_rate:.1f}%")
col4.metric("Avg Fraud Score", f"{avg_score:.3f}")

st.divider()

# ── Charts row 1 ──────────────────────────────────────────────────────────────

left, right = st.columns(2)

with left:
    st.subheader("Fraud Score Distribution")
    fig = px.histogram(
        df,
        x="fraud_score",
        nbins=40,
        color="is_fraudulent",
        color_discrete_map={True: "#EF553B", False: "#636EFA"},
        labels={"fraud_score": "Fraud Score", "is_fraudulent": "Fraudulent"},
        barmode="overlay",
        opacity=0.75,
    )
    fig.update_layout(legend_title_text="Fraudulent", margin=dict(t=20))
    st.plotly_chart(fig, use_container_width=True)

with right:
    st.subheader("Fraud by Merchant Category")
    cat_df = (
        df.groupby("merchant_category")["is_fraudulent"]
        .agg(total="count", fraud="sum")
        .assign(rate=lambda x: x["fraud"] / x["total"] * 100)
        .reset_index()
        .sort_values("rate", ascending=False)
    )
    fig2 = px.bar(
        cat_df,
        x="merchant_category",
        y="rate",
        color="rate",
        color_continuous_scale="Reds",
        labels={"merchant_category": "Category", "rate": "Fraud Rate (%)"},
    )
    fig2.update_layout(coloraxis_showscale=False, margin=dict(t=20))
    st.plotly_chart(fig2, use_container_width=True)

# ── Charts row 2 ──────────────────────────────────────────────────────────────

left2, right2 = st.columns(2)

with left2:
    st.subheader("Fraud by Location")
    loc_df = (
        df.groupby("location")["is_fraudulent"]
        .agg(total="count", fraud="sum")
        .assign(rate=lambda x: x["fraud"] / x["total"] * 100)
        .reset_index()
        .sort_values("rate", ascending=False)
    )
    fig3 = px.bar(
        loc_df,
        x="location",
        y="rate",
        color="rate",
        color_continuous_scale="Oranges",
        labels={"location": "State", "rate": "Fraud Rate (%)"},
    )
    fig3.update_layout(coloraxis_showscale=False, margin=dict(t=20))
    st.plotly_chart(fig3, use_container_width=True)

with right2:
    st.subheader("Transactions Over Time")
    if "created_at" in df.columns and not df["created_at"].isna().all():
        time_df = (
            df.set_index("created_at")
            .resample("1h")["is_fraudulent"]
            .agg(total="count", fraud="sum")
            .reset_index()
        )
        fig4 = px.line(
            time_df,
            x="created_at",
            y=["total", "fraud"],
            labels={"created_at": "Time", "value": "Count", "variable": "Series"},
            color_discrete_map={"total": "#636EFA", "fraud": "#EF553B"},
        )
        fig4.update_layout(legend_title_text="", margin=dict(t=20))
        st.plotly_chart(fig4, use_container_width=True)
    else:
        st.info("No timestamp data available for the time chart.")

st.divider()

# ── Recent transactions table ─────────────────────────────────────────────────

st.subheader("Recent Transactions (last 50)")
display_df = df.head(50)[
    ["transaction_id", "user_id", "amount", "merchant_category",
     "location", "fraud_score", "is_fraudulent", "confidence", "created_at"]
].copy()
display_df["fraud_score"] = display_df["fraud_score"].round(4)
display_df["amount"] = display_df["amount"].map("${:,.2f}".format)

st.dataframe(
    display_df,
    use_container_width=True,
    hide_index=True,
    column_config={
        "is_fraudulent": st.column_config.CheckboxColumn("Fraud?"),
        "fraud_score": st.column_config.ProgressColumn(
            "Fraud Score", min_value=0, max_value=1, format="%.4f"
        ),
    },
)

# ── Auto-refresh ─────────────────────────────────────────────────────────────

st.caption("Data cached for 30 s. Reload the page to see latest transactions.")
