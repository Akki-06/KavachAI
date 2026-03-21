import asyncio
import sounddevice as sd
import base64
import numpy as np
import os
import re
import pickle

from dotenv import load_dotenv
from sarvamai import AsyncSarvamAI

# ==============================
# Load environment variables
# ==============================

load_dotenv()

SARVAM_API_KEY = os.getenv("SARVAM_API_KEY")

if not SARVAM_API_KEY:
    raise ValueError("SARVAM_API_KEY not found in .env file")


from scam_engine import analyze_transcript

SAMPLERATE = 16000
CHANNELS = 1
CHUNK_SIZE = 4000  # 250ms of audio at 16kHz mono

# ==============================
# Audio configuration
# ==============================

# Keep the microphone stream aligned with the backend server/STT settings.
SAMPLERATE = 16000
CHANNELS = 1
CHUNK_SIZE = 2048


# ==============================
# Audio streaming handler
# ==============================

async def stream_audio():

    loop = asyncio.get_event_loop()
    audio_queue = asyncio.Queue()

    def callback(indata, frames, time, status):

        if status:
            print(f"[Audio Warning] {status}")

        audio_data = np.clip(indata * 2.5, -32768, 32767)

        audio_bytes = audio_data.astype(np.int16).tobytes()

        b64_chunk = base64.b64encode(audio_bytes).decode("utf-8")

        loop.call_soon_threadsafe(audio_queue.put_nowait, b64_chunk)

    print("\nKavachAI - Live Scam Detection Engine")
    print("--------------------------------------")
    print("Initialising Sarvam AI client...\n")

    client = AsyncSarvamAI(api_subscription_key=SARVAM_API_KEY)

    async with client.speech_to_text_streaming.connect(
        model="saaras:v3",
        mode="codemix",
        language_code="hi-IN",
        sample_rate=SAMPLERATE,
        input_audio_codec="pcm_s16le",
        high_vad_sensitivity=True,
        vad_signals=True,
    ) as ws:

        print("Connected! Microphone active. Speak to begin...\n")

        stream = sd.InputStream(
            samplerate=SAMPLERATE,
            channels=CHANNELS,
            dtype="int16",
            blocksize=CHUNK_SIZE,
            callback=callback,
        )

        risk_score = 0

        with stream:

            while True:

                b64_chunk = await audio_queue.get()

                await ws.transcribe(
                    audio=b64_chunk,
                    encoding="pcm_s16le",
                    sample_rate=SAMPLERATE,
                )

                try:

                    message = await asyncio.wait_for(ws.recv(), timeout=0.1)

                    msg_type = message.type

                    if msg_type == "events":

                        event = getattr(message.data, "event", None)

                        if event == "speech_start":
                            print("\n[VAD] Speech detected...")

                        elif event == "speech_end":
                            print("[VAD] Speech ended.")

                    elif msg_type == "data":

                        transcript = getattr(message.data, "transcript", "").strip()

                        if transcript:

                            risk_score, words, level = analyze_transcript(
                                transcript, risk_score
                            )

                            print(f"\nTranscript  : {transcript}")

                            if words:
                                print(f"Keywords    : {', '.join(words)}")

                            print(f"Risk Score  : {risk_score}")
                            print(f"Alert Level : {level}")

                            if risk_score >= 10:

                                print(
                                    "\n\033[1;41m ⚠ SCAM DETECTED — DO NOT SHARE ANY DETAILS \033[0m\n"
                                )

                    elif msg_type == "error":

                        print(
                            f"\n[API Error] {getattr(message.data, 'message', message.data)}"
                        )

                except asyncio.TimeoutError:
                    pass


# ==============================
# Start application
# ==============================

if __name__ == "__main__":

    try:
        asyncio.run(stream_audio())

    except KeyboardInterrupt:

        print("\nStopped listening.")
