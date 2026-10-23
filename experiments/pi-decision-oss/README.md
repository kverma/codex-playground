# Open-source decision optimizer verification
This experiment downloads a real, open-source DistilBERT SST-2 classifier on a GitHub-hosted CPU runner, caches probability predictions in JSON, fits a threshold on the training partition, gates promotion on validation F1, and reports an untouched test partition.

## Reproduce
```bash
pip install 'transformers==4.44.2' 'torch>=2.3,<3' 'numpy<2'
python experiments/pi-decision-oss/infer.py
node experiments/pi-decision-oss/optimize.mjs
```

## Limitations
This is an end-to-end **probability calibration smoke test** with a conventional binary classifier, **not** a Jev-compatible structured classifier or an actual Pi integration test. Its 32 manually written examples are small and may not improve from threshold fitting. Results are not evidence of generalization. Future work: real typed decision model, larger independent datasets, Pi adapter contract tests, ReAnchor parity, repeated seeds, uncertainty estimates, and latency.
