import json, os, sys, time, urllib.request, re
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "mt_probe2.txt"), "w")
def p(*a): print(*a); print(*a, file=log); log.flush()
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "probe"}), timeout=120) as r: return r.read()
for pair in ["en-mul", "en-ROMANCE", "mul-en", "ROMANCE-en"]:
    tok = json.loads(get(f"https://huggingface.co/Xenova/opus-mt-{pair}/resolve/main/tokenizer.json"))
    vocab = tok["model"]["vocab"]
    langs = [pc for pc, sc in vocab if re.fullmatch(r">>.+<<", pc)]
    p(pair, "vocab", len(vocab), "lang tokens", len(langs), " ".join(langs))
    p(pair, "added", json.dumps(tok.get("added_tokens"))[:300])
    p(pair, "pre", json.dumps(tok.get("pre_tokenizer"))[:300])
    cfg = json.loads(get(f"https://huggingface.co/Xenova/opus-mt-{pair}/resolve/main/config.json"))
    p(pair, "cfg", {k: cfg.get(k) for k in ["decoder_start_token_id", "eos_token_id", "pad_token_id", "vocab_size", "d_model", "decoder_layers", "decoder_attention_heads", "encoder_layers"]})
    info = json.loads(get(f"https://huggingface.co/api/models/Xenova/opus-mt-{pair}?blobs=true"))
    p(pair, "sizes", {s["rfilename"]: s.get("size") for s in info["siblings"] if "quantized" in s["rfilename"] or s["rfilename"].endswith("tokenizer.json")})
# Größen aller Paare (quantisierte Encoder+Merged-Decoder) und ob die Dateien existieren
models = json.loads(get("https://huggingface.co/api/models?author=Xenova&search=opus-mt&limit=500"))
for m in sorted(x["id"] for x in models):
    info = json.loads(get(f"https://huggingface.co/api/models/{m}?blobs=true"))
    files = {s["rfilename"]: s.get("size") for s in info["siblings"]}
    need = ["onnx/encoder_model_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx", "tokenizer.json", "config.json"]
    missing = [f for f in need if f not in files]
    total = sum(files.get(f, 0) or 0 for f in need)
    p("pair", m.split("opus-mt-")[1], "MB=%d" % (total / 1e6), "missing=" + ",".join(missing))
