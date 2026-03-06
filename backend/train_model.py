import os
import pandas as pd
import pickle

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.naive_bayes import MultinomialNB

import re

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(BASE_DIR, "model")

def clean_text(text):
    text = str(text).lower()
    text = re.sub(r'[^a-z0-9\s]', '', text)
    return text


# ===============================
# 1. Setup project paths
# ===============================

# Dataset folder
DATASET_DIR = os.path.join(BASE_DIR, "dataset")

# Dataset file paths
sms_path = os.path.join(DATASET_DIR, "sms_spam.csv")
indian_path = os.path.join(DATASET_DIR, "indian_spam.csv")


# ===============================
# 2. Load datasets
# ===============================

# SMS Spam dataset
sms_data = pd.read_csv(sms_path, encoding="latin-1")

# Keep only relevant columns
sms_data = sms_data[['v1', 'v2']]

# Rename columns
sms_data.columns = ['label', 'message']


# Indian spam dataset
indian_data = pd.read_csv(indian_path, encoding="latin-1")

# Rename columns
indian_data = indian_data.rename(columns={
    "Msg": "message",
    "Label": "label"
})


# ===============================
# 3. Combine datasets
# ===============================

data = pd.concat([sms_data, indian_data], ignore_index=True)


# ===============================
# 4. Convert labels
# ===============================

data['label'] = data['label'].map({'spam': 1, 'ham': 0})


# ===============================
# 5. Clean text
# ===============================

data['message'] = data['message'].apply(clean_text)


# ===============================
# 6. Prepare training data
# ===============================

X = data['message']
y = data['label']


# ===============================
# 7. Convert text → numeric features
# ===============================

vectorizer = TfidfVectorizer()

X_vectorized = vectorizer.fit_transform(X)


# ===============================
# 8. Train the model
# ===============================

model = MultinomialNB()

model.fit(X_vectorized, y)


# ===============================
# 9. Save trained model
# ===============================

model_file = os.path.join(MODEL_DIR, "model.pkl")
vectorizer_file = os.path.join(MODEL_DIR, "vectorizer.pkl")

with open(model_file, "wb") as f:
    pickle.dump(model, f)

with open(vectorizer_file, "wb") as f:
    pickle.dump(vectorizer, f)


# ===============================
# 10. Training complete
# ===============================

print("Model training completed successfully.")
print("Model saved to:", model_file)
print("Vectorizer saved to:", vectorizer_file)
print("Total training samples:", len(data))