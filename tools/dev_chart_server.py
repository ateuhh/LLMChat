#!/usr/bin/env python3
import os, json, base64, time, subprocess, datetime
from collections import defaultdict
from flask import Flask, send_from_directory, jsonify, request
import requests

# --- константы/окружение ---
REPO = os.environ.get("REPO_PATH", ".")
OUT_DIR = os.path.abspath(os.getcwd())
PNG_NAME = "commit_chart.png"
PNG_PATH = os.path.join(OUT_DIR, PNG_NAME)

K_API = os.environ.get("KANDINSKY_API_KEY", "")
K_SECRET = os.environ.get("KANDINSKY_SECRET_KEY", "")
FB_BASE = "https://api-key.fusionbrain.ai/"

USE_GEMINI = True
try:
    import google.generativeai as genai
except Exception:
    USE_GEMINI = False

app = Flask(__name__)

def run(cmd, cwd=None):
    p = subprocess.run(cmd, cwd=cwd, shell=True, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"{cmd}\n{p.stderr.strip()}")
    return p.stdout

def collect_commits_last7(repo_path):
    out = run('git log --since="7 days ago" --date=short --pretty=format:%ad', cwd=repo_path)
    counts = defaultdict(int)
    for line in out.splitlines():
        d = line.strip()
        if d:
            counts[d] += 1
    today = datetime.date.today()
    days = [(today - datetime.timedelta(days=i)).strftime("%Y-%m-%d") for i in range(6, -1, -1)]
    return [{"date": d, "count": counts.get(d, 0)} for d in days]

def prompt_via_gemini(data):
    # детерминированный fallback, если нет ключа или SDK недоступен
    pairs = ", ".join([f"{d['date']}:{d['count']}" for d in data])
    fallback = (
        "Draw a clean, minimal, data-faithful bar chart titled 'Commits per day (last 7 days)'. "
        "X-axis = dates (YYYY-MM-DD, left→right, exactly these 7 days). "
        "Y-axis starts at 0. Uniform solid bars, small gridlines, axis labels, numeric ticks. "
        "No 3D, no gradients, no decorations. "
        f"Data (date:count): {pairs}. Bar heights MUST equal counts."
    )

    key = os.environ.get("GOOGLE_API_KEY") or os.environ.get("GEMINI_API_KEY")
    if not USE_GEMINI or not key:
        return fallback

    genai.configure(api_key=key)
    model = genai.GenerativeModel("gemini-1.5-flash")
    system = ("You are a prompt engineer for a Kandinsky (FusionBrain) T2I model. "
              "Return ONLY a single plain English prompt for a flat bar chart; no markdown.")
    user = (fallback + " Canvas 1024x768 px, white background, black axes.")
    resp = model.generate_content([system, user])
    return (resp.text or "").strip() or fallback

# ---------- FusionBrain helpers ----------
def fb_headers():
    if not K_API or not K_SECRET:
        raise RuntimeError("KANDINSKY_API_KEY/KANDINSKY_SECRET_KEY не заданы в окружении.")
    return {"X-Key": f"Key {K_API}", "X-Secret": f"Secret {K_SECRET}"}

def fb_check_availability():
    r = requests.get(FB_BASE + "key/api/v1/pipeline/availability", headers=fb_headers(), timeout=20)
    r.raise_for_status()
    js = r.json()
    status = js.get("pipeline_status", "UNKNOWN")
    if status and status not in ("ENABLED", "AVAILABLE", "OK"):
        raise RuntimeError(f"Kandinsky недоступен сейчас (status={status}). Попробуйте позже.")
    return True

def fb_get_pipeline():
    r = requests.get(FB_BASE + "key/api/v1/pipelines", headers=fb_headers(), timeout=20)
    r.raise_for_status()
    data = r.json()
    if not data:
        raise RuntimeError("Список pipeline пуст.")
    return data[0]["id"]

def fb_generate(pipeline_id, prompt, width=1024, height=768):
    # ВАЖНО: не превышаем 1024 по стороне (требование API). :contentReference[oaicite:1]{index=1}
    params = {
        "type": "GENERATE",
        "numImages": 1,
        "width": int(min(width, 1024)),
        "height": int(min(height, 1024)),
        "generateParams": {"query": prompt},
    }
    files = {
        "pipeline_id": (None, pipeline_id),
        "params": (None, json.dumps(params), "application/json"),
    }
    r = requests.post(FB_BASE + "key/api/v1/pipeline/run", headers=fb_headers(), files=files, timeout=60)
    # Если сервис перегружен — вместо uuid вернётся объект со статусом очереди. :contentReference[oaicite:2]{index=2}
    r.raise_for_status()
    js = r.json()
    uuid = js.get("uuid")
    if not uuid:
        raise RuntimeError(f"FusionBrain не принял задачу: {js}")
    return uuid

def fb_poll(uuid, attempts=25, delay=3):
    for _ in range(attempts):
        r = requests.get(FB_BASE + f"key/api/v1/pipeline/status/{uuid}", headers=fb_headers(), timeout=30)
        r.raise_for_status()
        js = r.json()
        st = js.get("status")
        if st == "DONE":
            files = js.get("result", {}).get("files") or []
            if not files:
                raise RuntimeError("Статус DONE, но result.files пуст.")
            return files[0]
        if st == "FAIL":
            raise RuntimeError(f"FusionBrain FAIL: {js.get('errorDescription') or js}")
        time.sleep(delay)
    raise TimeoutError("Ожидание генерации превысило лимит.")

@app.route("/health")
def health():
    return jsonify({
        "repo_ok": os.path.isdir(os.path.join(REPO, ".git")),
        "k_api": bool(K_API), "k_secret": bool(K_SECRET),
    })

@app.route("/build-chart", methods=["POST"])
def build_chart():
    try:
        # 1) данные
        data = collect_commits_last7(REPO)
        # 2) промпт
        prompt = prompt_via_gemini(data)
        # 3) проверка доступности и генерация
        fb_check_availability()  # подскажет, если очередь/техработы
        pipeline = fb_get_pipeline()
        uuid = fb_generate(pipeline, prompt, width=1024, height=768)  # <=1024
        b64 = fb_poll(uuid)

        with open(PNG_PATH, "wb") as f:
            f.write(base64.b64decode(b64))

        return jsonify({"ok": True, "path": f"/{PNG_NAME}"})
    except Exception as e:
        # вернём причину — будет видно в логах/responseBody
        return jsonify({"ok": False, "error": str(e)}), 500

@app.route("/commit_chart.png")
def serve_png():
    return send_from_directory(OUT_DIR, PNG_NAME, mimetype="image/png")

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8766, debug=True)
