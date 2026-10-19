#!/usr/bin/env bash
# Erkundung: OCR-Modell (PaddleOCR ONNX, Lateinschrift) für Bild-Untertitel prüfen.
set -x
OUT=${1:-explore-out}; mkdir -p "$OUT"
pip install -q huggingface_hub onnxruntime numpy pillow onnx > /dev/null 2>&1
python3 - "$OUT" <<'PY' > "$OUT/ocr.txt" 2>&1
import sys, os, json, numpy as np
from huggingface_hub import list_repo_files, hf_hub_download, HfApi
out = sys.argv[1]
for repo in ["monkt/paddleocr-onnx", "docato/PaddleOCR_Mobile_Models"]:
    try:
        files = list_repo_files(repo)
        info = HfApi().model_info(repo, files_metadata=True)
        sizes = {s.rfilename: s.size for s in info.siblings}
        print("REPO", repo, "license", getattr(info, "card_data", None) and info.card_data.get("license"))
        for f in files:
            if "latin" in f.lower() or "readme" in f.lower() or "en" in f.lower().split("/")[-2:-1] or f.count("/") <= 1:
                print("  ", f, sizes.get(f))
    except Exception as e:
        print("REPO", repo, "ERR", e)
try:
    readme = open(hf_hub_download("monkt/paddleocr-onnx", "README.md")).read()
    print("README-----\n", readme[:6000])
except Exception as e:
    print("readme err", e)
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
def render(text, fg=(255,255,255), bg=(0,0,0,0)):
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 40)
    w = int(font.getlength(text)) + 40
    img = Image.new("RGBA", (w, 64), bg)
    d = ImageDraw.Draw(img)
    d.text((20, 8), text, font=font, fill=fg + (255,), stroke_width=3, stroke_fill=(0,0,0,255))
    return img
for repo, model, dictf in [
    ("monkt/paddleocr-onnx", "languages/latin/rec.onnx", "languages/latin/dict.txt"),
    ("monkt/paddleocr-onnx", "languages/english/rec.onnx", "languages/english/dict.txt"),
]:
    try:
        m = hf_hub_download(repo, model); dpath = hf_hub_download(repo, dictf)
    except Exception as e:
        print("DL ERR", model, e); continue
    chars = [l.rstrip("\n") for l in open(dpath, encoding="utf-8")]
    print("MODEL", model, os.path.getsize(m), "dict", len(chars), repr("".join(chars[:80])), "has_space", " " in chars)
    s = ort.InferenceSession(m)
    for i in s.get_inputs(): print("  in", i.name, i.shape, i.type)
    for o in s.get_outputs(): print("  out", o.name, o.shape, o.type)
    for text in ["Hello, how are you?", "Wo ist mein Bruder? Größe & Übermut", "- Where is he?"]:
        for mode in ["white_on_black", "black_on_white"]:
            img = render(text)
            base = Image.new("RGB", img.size, (0,0,0) if mode == "white_on_black" else (255,255,255))
            if mode == "black_on_white":
                # Text dunkel auf hell: Graustufen invertieren
                rgb = Image.alpha_composite(Image.new("RGBA", img.size, (0,0,0,255)), img).convert("L")
                rgb = Image.eval(rgb, lambda v: 255 - v).convert("RGB")
            else:
                rgb = Image.alpha_composite(Image.new("RGBA", img.size, (0,0,0,255)), img).convert("RGB")
            h = 48; w = max(16, int(rgb.width * h / rgb.height))
            arr = np.asarray(rgb.resize((w, h)), dtype=np.float32)[:, :, ::-1] / 255.0
            arr = (arr - 0.5) / 0.5
            x = arr.transpose(2, 0, 1)[None]
            y = s.run(None, {s.get_inputs()[0].name: x})[0][0]
            idx = y.argmax(-1); last = -1; res = []
            n = y.shape[-1]
            for k in idx:
                if k != last and k != 0:
                    res.append(chars[k - 1] if k - 1 < len(chars) else " ")
                last = k
            print("   ", mode, repr(text), "->", repr("".join(res)), "classes", n)
PY
cat "$OUT/ocr.txt" | head -50
