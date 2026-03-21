import asyncio
import json
import os
import io
import wave
import base64
import websockets
from datetime import datetime
from dotenv import load_dotenv
from sarvamai import AsyncSarvamAI

load_dotenv()
SARVAM_API_KEY = os.getenv("SARVAM_API_KEY")
if not SARVAM_API_KEY:
    raise ValueError("SARVAM_API_KEY not found in .env file")

from scam_engine import analyze_transcript


async def handle_client(websocket):
    print(f"[+] Phone connected: {websocket.remote_address}")
    risk_score = 0
    client = AsyncSarvamAI(api_subscription_key=SARVAM_API_KEY)

    try:
        async with client.speech_to_text_streaming.connect(
            model="saaras:v3",
            mode="codemix",
            language_code="en-IN",
            sample_rate=16000,
            input_audio_codec="pcm_s16le",
            high_vad_sensitivity=True,
            vad_signals=True,
        ) as sarvam_ws:

            print("[+] Connected to Sarvam AI. Transcribing...")
            chunk_count = 0

            # Use same pattern as live_kavach_stream.py:
            # For each audio chunk received from phone, send to Sarvam,
            # then immediately do a short non-blocking recv to pick up any response.
            async for raw_msg in websocket:
                try:
                    data = json.loads(raw_msg)
                except json.JSONDecodeError:
                    continue

                if "audio" not in data:
                    continue

                b64_audio = data["audio"]

                # ── Send to Sarvam ──
                await sarvam_ws.transcribe(
                    audio=b64_audio,
                    encoding="pcm_s16le",
                    sample_rate=16000,
                )

                # ── Non-blocking receive (0.1s timeout, same as live script) ──
                try:
                    message = await asyncio.wait_for(sarvam_ws.recv(), timeout=0.1)
                    msg_type = getattr(message, "type", None)

                    if msg_type == "events":
                        event = getattr(message.data, "event", None)
                        if event == "speech_start":
                            print("\n[VAD] Speech detected...")
                        elif event == "speech_end":
                            print("[VAD] Speech ended.")

                    elif msg_type == "data":
                        transcript = getattr(message.data, "transcript", "").strip()
                        if transcript:
                            risk_score, words, level = analyze_transcript(transcript, risk_score)

                            print(f"\nTranscript  : {transcript}")
                            if words:
                                print(f"Keywords    : {', '.join(words)}")
                            print(f"Risk Score  : {risk_score}")
                            print(f"Alert Level : {level}")

                            alert_payload = {
                                "transcript": transcript,
                                "risk_score": risk_score,
                                "level": level,
                                "detected_keywords": ", ".join(words),
                            }
                            await websocket.send(json.dumps(alert_payload))

                            if risk_score >= 10:
                                print("\n\033[1;41m ⚠ SCAM DETECTED \033[0m\n")

                    elif msg_type == "error":
                        err = getattr(message.data, "message", message.data)
                        print(f"\n[Sarvam Error] {err}")

                except asyncio.TimeoutError:
                    pass  # No response ready yet — keep sending audio

    except websockets.exceptions.ConnectionClosed:
        print(f"[-] Phone disconnected")

    except Exception as e:
        print(f"[-] Error: {e}")


async def main():
    HOST = "0.0.0.0"
    PORT = 8765
    print(f"Initializing KavachAI backend server...")
    print(f"Listening for phone connections on ws://{HOST}:{PORT}")
    async with websockets.serve(handle_client, HOST, PORT):
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[!] KavachAI Server stopped.")
