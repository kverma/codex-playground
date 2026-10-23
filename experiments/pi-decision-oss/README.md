# OSS classifier optimization benchmark (600 real examples)

GitHub Actions downloads the public GLUE SST-2 validation split and runs 600 distinct sentences through a pretrained DistilBERT SST-2 model on CPU. A fixed seed selects 300 positive and 300 negative examples and divides them into 200 training, 200 validation, and 200 untouched test examples. The optimizer fits a threshold on training, promotes only if validation F1 improves, and reports the untouched test metrics.

The model was trained on the separate SST-2 training split. This is a binary classification calibration experiment, **not** a Pi integration test or Jev-style typed decision model. Because the test is sampled from the same benchmark family used to train the classifier, it is not an out-of-distribution robustness test.

Artifacts: `predictions.json`, `report.json`, `report.md`.

Run via GitHub Actions workflow `Pi decision optimizer OSS verification`.
