# Synthetic FIT test fixture

`synthetic-running.fit` is generated with Garmin FIT SDK 21.171.0 and contains only deterministic synthetic data: one running session starting `2024-01-01T12:00:00Z`, 5,000 m in 1,800 seconds (6:00 min/km), maximum heart rate 150, two records and one lap. It exercises the real FIT decoder and is safe to commit.

Re-export/deduplication, malformed input, non-running/multi-session classification, coordinate bounds and local-only original-file storage are covered by the existing tests.
