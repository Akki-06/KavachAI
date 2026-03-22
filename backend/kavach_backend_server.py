import asyncio
import json
import os
import base64
import logging
import time
import websockets
from datetime import datetime
from dotenv import load_dotenv
from sarvamai import AsyncSarvamAI

load_dotenv()

# ── Logging (no transcripts in production) ───────────────────────────────────
LOG_LEVEL = logging.DEBUG if os.getenv("KAVACH_DEBUG", "").lower() == "true" else logging.INFO
logging.basicConfig(
    level=LOG_LEVEL,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("kavach")

# ── Config ───────────────────────────────────────────────────────────────────
SARVAM_API_KEY = os.getenv("SARVAM_API_KEY")
if not SARVAM_API_KEY:
    raise ValueError("SARVAM_API_KEY not found in .env file")

# Optional bearer token for WebSocket auth. If not set, auth is skipped
# (suitable for localhost-only deployments). Set in .env for production.
AUTH_TOKEN = os.getenv("KAVACH_AUTH_TOKEN", "")

MAX_CONNECTIONS = int(os.getenv("KAVACH_MAX_CONNECTIONS", "10"))
MAX_MSG_PER_SECOND = int(os.getenv("KAVACH_RATE_LIMIT", "50"))   # chunks/sec per client
MAX_AUDIO_BYTES = int(os.getenv("KAVACH_MAX_CHUNK_BYTES", "65536"))  # 64 KB per chunk

from scam_engine import analyze_transcript

# Track active connections
_active_connections: set = set()


def _validate_auth(websocket) -> bool:
    """Check bearer token if AUTH_TOKEN is configured."""
    if not AUTH_TOKEN:
        return True  # Auth disabled for local use
    auth_header = websocket.request_headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        return False
    return auth_header[len("Bearer "):] == AUTH_TOKEN


async def handle_client(websocket):
    # ── Connection limit ─────────────────────────────────────────────────────
    if len(_active_connections) >= MAX_CONNECTIONS:
        logger.warning("Connection limit reached. Rejecting new client.")
        await websocket.close(code=1008, reason="Server at capacity")
        return

    # ── Authentication ───────────────────────────────────────────────────────
    if not _validate_auth(websocket):
        logger.warning("Rejected unauthenticated WebSocket connection")
        await websocket.close(code=1008, reason="Unauthorized")
        return

    _active_connections.add(websocket)
    client_addr = websocket.remote_address
    logger.info(f"[+] Client connected: {client_addr}  (active: {len(_active_connections)})")

    # Rate-limiting state
    msg_count = 0
    window_start = time.monotonic()

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

            logger.info(f"[+] Sarvam AI stream opened for {client_addr}")

            async for raw_msg in websocket:

                # ── Rate limiting ─────────────────────────────────────────
                now = time.monotonic()
                if now - window_start >= 1.0:
                    msg_count = 0
                    window_start = now
                msg_count += 1
                if msg_count > MAX_MSG_PER_SECOND:
                    logger.warning(f"Rate limit exceeded for {client_addr}")
                    await websocket.close(code=1008, reason="Rate limit exceeded")
                    return

                # ── Message size guard ────────────────────────────────────
                if len(raw_msg) > MAX_AUDIO_BYTES * 2:  # base64 overhead
                    logger.warning(f"Oversized message from {client_addr}: {len(raw_msg)} bytes")
                    await websocket.close(code=1009, reason="Message too large")
                    return

                # ── JSON parse ────────────────────────────────────────────
                try:
                    data = json.loads(raw_msg)
                except (json.JSONDecodeError, ValueError):
                    logger.debug(f"Invalid JSON from {client_addr}")
                    continue

                if "audio" not in data or not isinstance(data["audio"], str):
                    continue

                # ── Base64 validation ─────────────────────────────────────
                try:
                    audio_bytes = base64.b64decode(data["audio"], validate=True)
                except Exception:
                    logger.debug(f"Invalid base64 audio from {client_addr}")
                    continue

                if len(audio_bytes) == 0 or len(audio_bytes) > MAX_AUDIO_BYTES:
                    logger.debug(f"Audio chunk out of range: {len(audio_bytes)} bytes")
                    continue

                # ── Send to Sarvam ────────────────────────────────────────
                await sarvam_ws.transcribe(
                    audio=data["audio"],
                    encoding="pcm_s16le",
                    sample_rate=16000,
                )

                # ── Non-blocking receive (0.1s timeout) ───────────────────
                try:
                    message = await asyncio.wait_for(sarvam_ws.recv(), timeout=0.1)
                    msg_type = getattr(message, "type", None)

                    if msg_type == "events":
                        event = getattr(message.data, "event", None)
                        if event == "speech_start":
                            logger.debug(f"[VAD] Speech start — {client_addr}")
                        elif event == "speech_end":
                            logger.debug(f"[VAD] Speech end — {client_addr}")

                    elif msg_type == "data":
                        transcript = getattr(message.data, "transcript", "").strip()
                        if transcript:
                            risk_score, words, level = analyze_transcript(transcript, risk_score)

                            # Only log transcript in debug mode (contains PII)
                            if LOG_LEVEL == logging.DEBUG:
                                logger.debug(f"Transcript: {transcript}")
                            logger.info(f"Risk {risk_score}/10 [{level}] keywords={words} addr={client_addr}")

                            alert_payload = {
                                "transcript": transcript,
                                "risk_score": risk_score,
                                "level": level,
                                "detected_keywords": ", ".join(words),
                            }
                            await websocket.send(json.dumps(alert_payload))

                            if risk_score >= 10:
                                logger.warning(f"SCAM DETECTED (score {risk_score}) for {client_addr}")

                    elif msg_type == "error":
                        err = getattr(message.data, "message", str(message.data))
                        logger.error(f"Sarvam API error: {err}")
                        # Notify client of backend error
                        await websocket.send(json.dumps({
                            "error": "Transcription service error",
                            "risk_score": risk_score,
                            "level": "UNKNOWN"
                        }))

                except asyncio.TimeoutError:
                    pass  # No response ready yet — keep sending audio

    except websockets.exceptions.ConnectionClosed as e:
        logger.info(f"[-] Client disconnected: {client_addr} (code={e.code})")

    except Exception as e:
        logger.error(f"[-] Unexpected error for {client_addr}: {type(e).__name__}: {e}")

    finally:
        _active_connections.discard(websocket)
        logger.info(f"Active connections: {len(_active_connections)}")


async def main():
    HOST = os.getenv("KAVACH_HOST", "0.0.0.0")
    PORT = int(os.getenv("KAVACH_PORT", "8765"))

    auth_status = "ENABLED" if AUTH_TOKEN else "DISABLED (set KAVACH_AUTH_TOKEN for production)"
    logger.info("=" * 60)
    logger.info("KavachAI Backend Server")
    logger.info(f"Listening on  : ws://{HOST}:{PORT}")
    logger.info(f"Auth          : {auth_status}")
    logger.info(f"Max clients   : {MAX_CONNECTIONS}")
    logger.info(f"Rate limit    : {MAX_MSG_PER_SECOND} msg/s per client")
    logger.info(f"Debug mode    : {LOG_LEVEL == logging.DEBUG}")
    logger.info("=" * 60)

    async with websockets.serve(
        handle_client,
        HOST,
        PORT,
        max_size=MAX_AUDIO_BYTES * 2,  # Enforce at WebSocket layer too
    ):
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("KavachAI Server stopped.")
