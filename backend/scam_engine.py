import os
import re
import hashlib
import pickle
import logging

logger = logging.getLogger("kavach.engine")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_PATH = os.path.join(BASE_DIR, "backend", "model", "model.pkl")
VECT_PATH = os.path.join(BASE_DIR, "backend", "model", "vectorizer.pkl")
HASH_PATH = os.path.join(BASE_DIR, "backend", "model", "hashes.txt")

model = None
vectorizer = None


def _file_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def _load_model_safely():
    """Load pickle model only after verifying file integrity via SHA-256."""
    global model, vectorizer

    if not os.path.exists(MODEL_PATH) or not os.path.exists(VECT_PATH):
        logger.warning("ML model files not found — keyword-only scoring active.")
        return

    # If hash file exists, verify integrity before loading
    if os.path.exists(HASH_PATH):
        try:
            expected = {}
            with open(HASH_PATH) as f:
                for line in f:
                    parts = line.strip().split("  ", 1)
                    if len(parts) == 2:
                        expected[parts[1]] = parts[0]

            model_name = os.path.basename(MODEL_PATH)
            vect_name = os.path.basename(VECT_PATH)

            if model_name in expected:
                actual = _file_sha256(MODEL_PATH)
                if actual != expected[model_name]:
                    logger.error("model.pkl integrity check FAILED — aborting ML load.")
                    return

            if vect_name in expected:
                actual = _file_sha256(VECT_PATH)
                if actual != expected[vect_name]:
                    logger.error("vectorizer.pkl integrity check FAILED — aborting ML load.")
                    return
        except Exception as e:
            logger.error(f"Hash verification error: {e} — aborting ML load.")
            return

    try:
        with open(MODEL_PATH, "rb") as f:
            model = pickle.load(f)
        with open(VECT_PATH, "rb") as f:
            vectorizer = pickle.load(f)
        logger.info("ML model loaded successfully.")
    except Exception as e:
        logger.warning(f"ML model load failed: {e}. Keyword-only scoring active.")
        model = None
        vectorizer = None


_load_model_safely()


# ── Scam keyword dictionary ───────────────────────────────────────────────────
# Weights are additive per call. Tuned for Indian phone scam patterns.
# Covers: OTP/UPI fraud, KYC scams, impersonation (bank/police/CBI/TRAI),
#         lottery fraud, loan fraud, insurance fraud, tech-support scams.

scam_keywords = {
    # ─── Highest-risk terms (direct ask for secrets) ────────────────────── 3 pts
    "otp": 3,
    "one time password": 3,
    "upi pin": 3,
    "atm pin": 3,
    "pin": 3,
    "password": 3,
    "cvv": 3,
    "cvc": 3,
    "card number": 3,
    "debit card": 3,
    "credit card": 3,
    "net banking": 3,
    "internet banking": 3,
    "remote access": 3,
    "anydesk": 3,
    "teamviewer": 3,
    "screen share": 3,
    "download app": 3,
    "install app": 3,

    # ─── UPI / Payment fraud ────────────────────────────────────────────── 3 pts
    "upi": 3,
    "google pay": 3,
    "gpay": 3,
    "phonepe": 3,
    "paytm": 3,
    "bhim": 3,
    "transfer money": 3,
    "send money": 3,
    "payment link": 3,
    "collect request": 3,
    "approve payment": 3,

    # ─── Banking & Identity ──────────────────────────────────────────────── 2 pts
    "bank account": 2,
    "account number": 2,
    "ifsc": 2,
    "aadhaar": 2,
    "aadhar": 2,
    "pan card": 2,
    "pan number": 2,
    "kyc": 2,
    "know your customer": 2,
    "update kyc": 2,
    "kyc pending": 2,
    "kyc expired": 2,
    "kyc verification": 2,
    "verify account": 2,
    "verify identity": 2,
    "bank": 2,
    "sbi": 2,
    "hdfc": 2,
    "icici": 2,
    "axis bank": 2,
    "pnb": 2,
    "rbi": 2,
    "reserve bank": 2,
    "nbfc": 2,

    # ─── Threat / Urgency language ───────────────────────────────────────── 2 pts
    "account blocked": 2,
    "account suspended": 2,
    "account deactivated": 2,
    "block": 2,
    "suspend": 2,
    "freeze": 2,
    "legal action": 2,
    "arrest": 2,
    "arrested": 2,
    "warrant": 2,
    "fir": 2,
    "case registered": 2,
    "money laundering": 2,
    "cybercrime": 2,
    "fraud": 2,
    "illegal": 2,
    "last chance": 2,
    "24 hours": 2,
    "immediately": 2,
    "urgent": 2,
    "emergency": 2,
    "do not tell anyone": 2,
    "keep this confidential": 2,
    "don't disconnect": 2,

    # ─── Impersonation (government / authority) ──────────────────────────── 2 pts
    "cbi": 2,
    "ib officer": 2,
    "intelligence bureau": 2,
    "income tax": 2,
    "it department": 2,
    "enforcement directorate": 2,
    "ed officer": 2,
    "trai": 2,
    "telecom department": 2,
    "department of telecom": 2,
    "dot": 2,
    "police": 2,
    "commissioner": 2,
    "magistrate": 2,
    "court": 2,
    "supreme court": 2,
    "high court": 2,
    "government officer": 2,
    "official": 2,
    "ministry": 2,

    # ─── Lottery / Prize / Refund fraud ─────────────────────────────────── 2 pts
    "lottery": 2,
    "prize": 2,
    "winner": 2,
    "won": 2,
    "lucky draw": 2,
    "bumper prize": 2,
    "refund": 2,
    "cashback": 2,
    "compensation": 2,
    "claim your prize": 2,
    "processing fee": 2,
    "registration fee": 2,
    "token amount": 2,

    # ─── Loan / Insurance fraud ──────────────────────────────────────────── 2 pts
    "pre-approved loan": 2,
    "instant loan": 2,
    "loan approved": 2,
    "personal loan": 2,
    "emi": 2,
    "insurance policy": 2,
    "policy lapse": 2,
    "surrender policy": 2,
    "maturity amount": 2,
    "bonus amount": 2,

    # ─── Generic suspicious terms ────────────────────────────────────────── 1 pt
    "verify": 1,
    "confirm": 1,
    "click link": 1,
    "link": 1,
    "payment": 1,
    "account": 1,
    "number": 1,
    "details": 1,
    "personal details": 1,
    "share": 1,
    "provide": 1,
    "give me": 1,
    "tell me": 1,
    "help me": 1,
    "problem": 1,
    "issue": 1,
    "complaint": 1,
    "service": 1,
    "call back": 1,
    "call us": 1,
}

# ── Hindi / Hinglish terms (transliterated) ───────────────────────────────────
# Common scam phrases heard in Indian phone fraud calls
hinglish_keywords = {
    "paisa bhejo": 3,       # send money
    "abhi transfer karo": 3, # transfer now
    "otp batao": 3,         # tell me OTP
    "pin batao": 3,         # tell me PIN
    "password batao": 3,    # tell me password
    "kyc update karo": 2,   # update KYC
    "account band ho jayega": 2,  # account will be blocked
    "giraftaar": 2,         # arrest
    "pakad lenge": 2,       # will catch you
    "case ho jayega": 2,    # case will be filed
    "lottery jeeta hai": 2, # you won a lottery
    "inam jeeta hai": 2,    # you won a prize
    "paise wapas milenge": 2,  # money will be returned
    "link par click karo": 2,  # click on the link
    "app install karo": 2,  # install the app
    "screen share karo": 2, # share your screen
    "loan approved ho gaya": 2,  # loan is approved
}

scam_keywords.update(hinglish_keywords)


def ml_predict(text: str) -> int:
    if model is None or vectorizer is None:
        return 0
    try:
        text_lower = text.lower()
        vector = vectorizer.transform([text_lower])
        return int(model.predict(vector)[0])
    except Exception as e:
        logger.debug(f"ML predict error: {e}")
        return 0


def analyze_transcript(text: str, current_score: int):
    text_lower = text.lower()
    detected = []
    chunk_score = 0

    for keyword, weight in scam_keywords.items():
        # Word/phrase boundary matching
        pattern = r"(?<!\w)" + re.escape(keyword) + r"(?!\w)"
        if re.search(pattern, text_lower):
            chunk_score += weight
            detected.append(keyword)

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
