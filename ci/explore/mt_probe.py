"""Erkundung: Opus-MT als ONNX (Xenova) – verfügbare Sprachpaare, Dateien, Tokenizer, Ablauf, Tempo."""
import json, os, sys, time, urllib.request
import numpy as np
import onnxruntime as ort

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "mt_probe.txt"), "w")
def p(*a):
    print(*a); print(*a, file=log); log.flush()

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "probe"}), timeout=120) as r:
        return r.read()

models = json.loads(get("https://huggingface.co/api/models?author=Xenova&search=opus-mt&limit=500"))
pairs = sorted(m["id"].split("/")[1].replace("opus-mt-", "") for m in models)
p("xenova pairs", len(pairs), " ".join(pairs))
for repo in ["Xenova/opus-mt-en-de"]:
    info = json.loads(get(f"https://huggingface.co/api/models/{repo}?blobs=true"))
    for s in info.get("siblings", []):
        p("file", s["rfilename"], s.get("size"))

base = "https://huggingface.co/Xenova/opus-mt-en-de/resolve/main/"
d = os.path.join(OUT, "m"); os.makedirs(d, exist_ok=True)
for f in ["config.json", "generation_config.json", "tokenizer.json", "tokenizer_config.json",
          "onnx/encoder_model_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx"]:
    path = os.path.join(d, f.replace("/", "_"))
    if not os.path.exists(path):
        open(path, "wb").write(get(base + f))
    p("got", f, os.path.getsize(path))
cfg = json.load(open(os.path.join(d, "config.json")))
p("config", {k: cfg.get(k) for k in ["decoder_start_token_id", "eos_token_id", "pad_token_id", "vocab_size", "d_model", "decoder_layers", "decoder_attention_heads", "max_length", "num_beams", "bad_words_ids"]})
p("generation_config", open(os.path.join(d, "generation_config.json")).read())
tok = json.load(open(os.path.join(d, "tokenizer.json")))
p("tokenizer keys", list(tok.keys()))
p("normalizer", json.dumps(tok.get("normalizer"))[:600])
p("pre_tokenizer", json.dumps(tok.get("pre_tokenizer"))[:600])
p("post_processor", json.dumps(tok.get("post_processor"))[:600])
p("decoder", json.dumps(tok.get("decoder"))[:600])
model = tok["model"]
p("model type", model.get("type"), "unk_id", model.get("unk_id"), "vocab size", len(model.get("vocab", [])), "first", model.get("vocab", [])[:8])
p("added_tokens", json.dumps(tok.get("added_tokens"))[:800])
p("tokenizer_config", open(os.path.join(d, "tokenizer_config.json")).read()[:800])

enc = ort.InferenceSession(os.path.join(d, "onnx_encoder_model_quantized.onnx"))
dec = ort.InferenceSession(os.path.join(d, "onnx_decoder_model_merged_quantized.onnx"))
for s, n in [(enc, "enc"), (dec, "dec")]:
    p(n, "inputs", [(i.name, i.type, i.shape) for i in s.get_inputs()])
    p(n, "outputs", [(o.name, o.shape) for o in s.get_outputs()][:6], "...", len(s.get_outputs()))

# Unigram-Tokenisierung (Viterbi) wie später in Kotlin
vocab = model["vocab"]
piece_id = {pc: i for i, (pc, sc) in enumerate(vocab)}
scores = [sc for pc, sc in vocab]
maxlen = max(len(pc) for pc, _ in vocab)
unk = model.get("unk_id", 1)
def encode(text):
    text = "▁" + text.strip().replace(" ", "▁")
    n = len(text)
    best = [(-1e18, -1, -1)] * (n + 1)
    best[0] = (0.0, -1, -1)
    for i in range(n):
        if best[i][0] <= -1e17: continue
        found = False
        for j in range(i + 1, min(n, i + maxlen) + 1):
            pid = piece_id.get(text[i:j])
            if pid is not None:
                sc = best[i][0] + scores[pid]
                if sc > best[j][0]: best[j] = (sc, i, pid)
                found = True
        if not found:
            sc = best[i][0] - 10.0
            if sc > best[i + 1][0]: best[i + 1] = (sc, i, unk)
    ids, k = [], n
    while k > 0:
        _, prev, pid = best[k]; ids.append(pid); k = prev
    return ids[::-1]

eos = cfg["eos_token_id"]; pad = cfg["pad_token_id"]; start = cfg["decoder_start_token_id"]
L = cfg["decoder_layers"]; H = cfg["decoder_attention_heads"]; D = cfg["d_model"] // H
def translate(text):
    ids = encode(text) + [eos]
    a = np.array([ids], dtype=np.int64); m = np.ones_like(a)
    hidden = enc.run(None, {"input_ids": a, "attention_mask": m})[0]
    names = [i.name for i in dec.get_inputs()]
    past = {n: np.zeros((1, H, 0, D), dtype=np.float32) for n in names if n.startswith("past_key_values")}
    out_names = [o.name for o in dec.get_outputs()]
    tokens = [start]
    for step in range(256):
        feed = {"input_ids": np.array([[tokens[-1]]], dtype=np.int64), "encoder_attention_mask": m,
                "encoder_hidden_states": hidden, "use_cache_branch": np.array([step > 0])}
        feed.update(past)
        res = dict(zip(out_names, dec.run(None, feed)))
        logits = res["logits"][0, -1]
        logits[pad] = -1e9
        nxt = int(np.argmax(logits))
        for n in list(past):
            o = n.replace("past_key_values", "present")
            if step == 0 or ".decoder." in n:
                past[n] = res[o]
        if nxt == eos: break
        tokens.append(nxt)
    pieces = [vocab[t][0] for t in tokens[1:]]
    return "".join(pieces).replace("▁", " ").strip()

for s in ["Hello, how are you?", "I told you we should never have come here.", "Get out of the car! Now!",
          "The weather is nice today, isn't it?", "<i>Where is my brother?</i>"]:
    t0 = time.time(); r = translate(s); p("MT", repr(s), "->", repr(r), "%.0f ms" % ((time.time() - t0) * 1000))
