"""Run an actual open-source sentiment decision model on reproducible examples.
This tests a binary probability adapter, NOT Jev's structured multi-question API.
"""
import json
from pathlib import Path
from transformers import pipeline

ROOT = Path(__file__).parent
OUT = ROOT / "results"
OUT.mkdir(exist_ok=True)
# A compact, public, pretrained binary classifier suitable for GitHub-hosted CPU.
MODEL = "distilbert/distilbert-base-uncased-finetuned-sst-2-english"
examples = [
 ("I loved the movie and would watch it again",1),("This was an excellent experience",1),
 ("Wonderful service and delicious food",1),("I am delighted with the result",1),
 ("The design is elegant and very useful",1),("An impressive and enjoyable presentation",1),
 ("The device works perfectly",1),("A fantastic performance",1),
 ("This is a terrible experience",0),("I hated the movie",0),
 ("The product is broken and unusable",0),("Very disappointing customer service",0),
 ("This is frustrating and poorly designed",0),("I regret buying it",0),
 ("A dreadful performance",0),("The meal was awful",0),
 ("I enjoyed most of the film but the ending was weak",1),
 ("Despite minor flaws this is a great product",1),
 ("It is not perfect but I recommend it",1),
 ("The support team was slow but ultimately helpful",1),
 ("Although it looks nice it fails constantly",0),
 ("The first hour was good but the film was disappointing",0),
 ("It sounded promising but I would not recommend it",0),
 ("The package arrived quickly but the device never worked",0),
 ("The software is intuitive and stable",1),("The quality exceeded my expectations",1),
 ("The documentation was clear and helpful",1),("The update fixed all my problems",1),
 ("The battery life is terrible",0),("The interface is confusing and slow",0),
 ("It crashes frequently",0),("The instructions are misleading",0),
]
clf = pipeline("text-classification",model=MODEL,device=-1)
rows=[]
for i,(sentence,label) in enumerate(examples):
    predictions=clf(sentence,top_k=None,truncation=True)
    positive=next(float(p["score"]) for p in predictions if p["label"].upper() in ("POSITIVE","LABEL_1"))
    rows.append({"id":i,"text":sentence,"label":label,"probability":positive,
                 "split":"train" if i<16 else ("validation" if i<24 else "test")})
(OUT/"predictions.json").write_text(json.dumps({"model":MODEL,"rows":rows},indent=2))
print(f"Real model inference completed: {len(rows)} examples")
