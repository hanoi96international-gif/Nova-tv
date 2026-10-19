#!/usr/bin/env python3
"""Wandelt die OPUS-MT-Modelle (Xenova, ONNX int8) ins ORT-Format um und lädt sie als Release-Dateien hoch.

Fire TV Sticks mit 32-Bit-ARM können ein ONNX-Modell nicht selbst umwandeln (braucht ~500 MB) und
blenden die ORT-Datei nur ein (mmap). Optimierungsstufe BASIC: plattformunabhängig (x86 → ARM).
Aufruf: convert.py <pairs.txt> <release-tag>
"""
import os
import subprocess
import sys

import onnxruntime as ort
from huggingface_hub import hf_hub_download

pairs = [p.strip() for p in open(sys.argv[1]) if p.strip()]
tag = sys.argv[2]
existing = set(subprocess.run(["gh", "release", "view", tag, "--json", "assets", "-q", ".assets[].name"],
                              capture_output=True, text=True).stdout.split())
for pair in pairs:
    for part, remote in [("encoder", "onnx/encoder_model_quantized.onnx"), ("decoder", "onnx/decoder_model_merged_quantized.onnx")]:
        name = f"opus-mt-{pair}.{part}.ort"
        if name in existing:
            print("vorhanden", name, flush=True)
            continue
        try:
            src = hf_hub_download(f"Xenova/opus-mt-{pair}", remote)
        except Exception as e:  # Paar gibt es nicht als ONNX: überspringen
            print("FEHLT", pair, part, e, flush=True)
            continue
        options = ort.SessionOptions()
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        options.optimized_model_filepath = name
        options.add_session_config_entry("session.save_model_format", "ORT")
        ort.InferenceSession(src, options, providers=["CPUExecutionProvider"])
        print("umgewandelt", name, os.path.getsize(src) // 1_000_000, "MB ->", os.path.getsize(name) // 1_000_000, "MB", flush=True)
        subprocess.run(["gh", "release", "upload", tag, name, "--clobber"], check=True)
        os.remove(name)
        os.remove(src)
