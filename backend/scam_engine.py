import os
import re
import pickle

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_PATH = os.path.join(BASE_DIR, "model", "model.pkl")
VECT_PATH = os.path.join(BASE_DIR, "model", "vectorizer.pkl")

try:
    model = pickle.load(open(MODEL_PATH, "rb"))
    vectorizer = pickle.load(open(VECT_PATH, "rb"))
except FileNotFoundError:
    print("Warning: ML model files not found. Ensure train_model.py has been run. Will use keyword scoring only.")
    model = None
    vectorizer = None

def ml_predict(text):
    if model is None or vectorizer is None:
        return 0
    text = text.lower()
    vector = vectorizer.transform([text])
    return model.predict(vector)[0]

scam_keywords = {
    "otp": 3, "kyc": 2, "bank": 2, "account": 1, "verify": 2,
    "upi": 3, "payment": 2, "password": 3, "pin": 3, "fraud": 2,
    "refund": 2, "lottery": 3, "prize": 2, "block": 2, "suspend": 2,
}

def analyze_transcript(text: str, current_score: int):
    text_lower = text.lower()
    detected = []
    chunk_score = 0

    for word, weight in scam_keywords.items():
        if re.search(r"\b" + word + r"\b", text_lower):
            chunk_score += weight
            detected.append(word)

    if ml_predict(text) == 1:
        chunk_score += 3

    new_score = current_score + chunk_score

    if new_score <= 3:
        level = "SAFE"
    elif new_score <= 6:
        level = "SUSPICIOUS"
    elif new_score <= 9:
        level = "HIGH RISK"
    else:
        level = "SCAM DETECTED"

    return new_score, detected, level
