"""
KavachAI — Model Trainer
=========================
Trains a Naive Bayes scam/spam classifier on all available datasets.

Usage:
    # First download datasets:
    python download_datasets.py

    # Then train:
    python train_model.py

Outputs (in backend/model/):
    model.pkl       — trained MultinomialNB classifier
    vectorizer.pkl  — TF-IDF vectorizer
    hashes.txt      — SHA-256 integrity hashes (used by scam_engine.py)
    report.txt      — accuracy / classification report
"""

import os
import re
import glob
import pickle
import hashlib
import logging

import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.naive_bayes import MultinomialNB
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, accuracy_score

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger("kavach.train")

BASE_DIR   = os.path.dirname(os.path.abspath(__file__))
DATASET_DIR = os.path.join(BASE_DIR, "dataset")
MODEL_DIR  = os.path.join(BASE_DIR, "model")
os.makedirs(MODEL_DIR, exist_ok=True)


def clean_text(text: str) -> str:
    text = str(text).lower().strip()
    # Keep alphanumeric, spaces, and common Devanagari Unicode range for Hindi
    text = re.sub(r"[^\w\s\u0900-\u097F]", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def load_dataset(path: str) -> pd.DataFrame | None:
    """Load a CSV with any of the known column naming conventions."""
    try:
        df = pd.read_csv(path, encoding="utf-8", errors="ignore")
    except Exception:
        try:
            df = pd.read_csv(path, encoding="latin-1", errors="ignore")
        except Exception as e:
            logger.warning(f"Failed to read {path}: {e}")
            return None

    # Normalise column names
    df.columns = [c.strip().lower() for c in df.columns]

    # Find label column
    label_col = next((c for c in df.columns
                      if c in ("label", "v1", "class", "category", "spam")), None)
    # Find text column
    text_col = next((c for c in df.columns
                     if c in ("text", "v2", "message", "msg", "sms", "content")), None)

    if label_col is None or text_col is None:
        logger.warning(f"Skipping {os.path.basename(path)} — cannot find label/text columns. "
                       f"Columns found: {list(df.columns)}")
        return None

    result = pd.DataFrame({
        "label": df[label_col].astype(str).str.strip().str.lower(),
        "text": df[text_col].astype(str)
    })
    return result.dropna()


def normalise_labels(df: pd.DataFrame) -> pd.DataFrame:
    """Convert any label format to integer 1=spam/scam, 0=ham/legit."""
    mapping = {
        "spam": 1, "scam": 1, "fraud": 1, "1": 1, "yes": 1, "true": 1,
        "ham":  0, "legit": 0, "safe": 0, "0": 0, "no":  0, "false": 0,
    }
    df["label"] = df["label"].map(mapping)
    return df.dropna(subset=["label"])


def main():
    logger.info("=" * 60)
    logger.info("KavachAI Model Trainer")
    logger.info("=" * 60)

    # ── 1. Load all CSV datasets ──────────────────────────────────────────────
    csv_files = glob.glob(os.path.join(DATASET_DIR, "*.csv"))
    if not csv_files:
        logger.error(f"No CSV files in {DATASET_DIR}. Run download_datasets.py first.")
        return

    frames = []
    for path in sorted(csv_files):
        df = load_dataset(path)
        if df is not None and len(df) > 0:
            df = normalise_labels(df)
            df["label"] = df["label"].astype(int)
            frames.append(df)
            logger.info(f"  Loaded: {os.path.basename(path):50s} {len(df):>6,} rows  "
                        f"(spam={df['label'].sum():,}, ham={(df['label']==0).sum():,})")

    if not frames:
        logger.error("No valid datasets loaded. Exiting.")
        return

    data = pd.concat(frames, ignore_index=True)

    # ── 2. Clean text ─────────────────────────────────────────────────────────
    data["text"] = data["text"].apply(clean_text)
    data = data[data["text"].str.len() > 3]  # drop near-empty rows

    logger.info(f"\nTotal training samples: {len(data):,}")
    logger.info(f"  Scam/spam  : {data['label'].sum():,}")
    logger.info(f"  Ham/legit  : {(data['label']==0).sum():,}")

    if data["label"].nunique() < 2:
        logger.error("Only one class present. Need both spam and ham samples.")
        return

    # ── 3. Train / test split ─────────────────────────────────────────────────
    X = data["text"]
    y = data["label"]
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.15, random_state=42, stratify=y)

    # ── 4. Vectorise ──────────────────────────────────────────────────────────
    vectorizer = TfidfVectorizer(
        ngram_range=(1, 2),        # unigrams + bigrams
        max_features=20000,
        sublinear_tf=True,
        min_df=2,
    )
    X_train_vec = vectorizer.fit_transform(X_train)
    X_test_vec  = vectorizer.transform(X_test)

    # ── 5. Train ──────────────────────────────────────────────────────────────
    model = MultinomialNB(alpha=0.1)
    model.fit(X_train_vec, y_train)

    # ── 6. Evaluate ───────────────────────────────────────────────────────────
    y_pred = model.predict(X_test_vec)
    acc    = accuracy_score(y_test, y_pred)
    report = classification_report(y_test, y_pred,
                                   target_names=["Ham/Legit", "Spam/Scam"])

    logger.info(f"\nTest Accuracy: {acc * 100:.2f}%")
    logger.info(f"\nClassification Report:\n{report}")

    # ── 7. Save model ─────────────────────────────────────────────────────────
    model_path = os.path.join(MODEL_DIR, "model.pkl")
    vect_path  = os.path.join(MODEL_DIR, "vectorizer.pkl")

    with open(model_path, "wb") as f:
        pickle.dump(model, f)
    with open(vect_path, "wb") as f:
        pickle.dump(vectorizer, f)

    # ── 8. Write integrity hashes ─────────────────────────────────────────────
    hash_path = os.path.join(MODEL_DIR, "hashes.txt")
    model_hash = _sha256(model_path)
    vect_hash  = _sha256(vect_path)
    with open(hash_path, "w") as f:
        f.write(f"{model_hash}  model.pkl\n")
        f.write(f"{vect_hash}  vectorizer.pkl\n")
    logger.info(f"\nIntegrity hashes written → {hash_path}")

    # ── 9. Save report ────────────────────────────────────────────────────────
    report_path = os.path.join(MODEL_DIR, "report.txt")
    with open(report_path, "w") as f:
        f.write(f"KavachAI Model Training Report\n")
        f.write(f"Training samples: {len(X_train):,}\n")
        f.write(f"Test samples    : {len(X_test):,}\n")
        f.write(f"Test accuracy   : {acc * 100:.2f}%\n\n")
        f.write(report)
    logger.info(f"Report saved → {report_path}")

    logger.info("\nModel training complete.")
    logger.info(f"  model.pkl      → {model_path}")
    logger.info(f"  vectorizer.pkl → {vect_path}")


if __name__ == "__main__":
    main()
