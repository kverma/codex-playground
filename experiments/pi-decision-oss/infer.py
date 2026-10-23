"""Real inference on 600 held-out SST-2 validation sentences.
Model was trained on SST-2 training data, not these evaluation examples.
This validates a binary classifier optimizer, not Pi/Jev integration.
"""
import json, random
from pathlib import Path
from datasets import load_dataset
from transformers import pipeline

ROOT = Path(__file__).parent
OUT = ROOT / "results"
OUT.mkdir(exist_ok=True)
MODEL = "distilbert/distilbert-base-uncased-finetuned-sst-2-english"
DATASET = "glue/sst2 validation"
source = load_dataset("glue", "sst2", split="validation")
positives = [dict(x) for x in source if x["label"] == 1]
negatives = [dict(x) for x in source if x["label"] == 0]
rng = random.Random(20261023)
rng.shuffle(positives)
rng.shuffle(negatives)
# 300 positive, 300 negative, then seeded shuffle for deterministic balanced partitions.
assert len(positives) >= 300 and len(negatives) >= 300
examples = positives[:300] + negatives[:300]
rng.shuffle(examples)
clf = pipeline("text-classification", model=MODEL, device=-1)
predictions = clf([x["sentence"] for x in examples], top_k=None, truncation=True,
                  batch_size=16)
rows = []
for i,(item,preds) in enumerate(zip(examples,predictions)):
    positive = next(float(p["score"]) for p in preds
                    if p["label"].upper() in ("POSITIVE","LABEL_1"))
    rows.append({"id":int(item["idx"]), "text":item["sentence"],
                 "label":int(item["label"]), "probability":positive,
                 "split":"train" if i<200 else ("validation" if i<400 else "test")})
assert len(rows)==600 and len({r["id"] for r in rows})==600
(OUT/"predictions.json").write_text(json.dumps({"model":MODEL,"dataset":DATASET,
    "seed":20261023,"rows":rows},indent=2))
print("Real model inference completed: 600 SST-2 validation examples (200/200/200)")
