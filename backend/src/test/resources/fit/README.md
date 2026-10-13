# Synthetic FIT test fixture

`synthetic-running.fit` is generated with Garmin FIT SDK 21.171.0 and contains only deterministic synthetic data: one running session starting `2024-01-01T12:00:00Z`, 5,000 m in 1,800 seconds (6:00 min/km), maximum heart rate 150, two records and one lap. It exercises the real FIT decoder and is safe to commit.

`synthetic-running-2.fit` is also generated/re-encoded with Garmin FIT SDK 21.171.0; it starts `2024-01-08T12:00:00Z`, covers 7,000 m in 1,800 seconds (4:17 min/km), and has two records and one lap. It is distinct synthetic data for multi-file upload tests.

Re-export/deduplication, malformed input, non-running/multi-session classification, coordinate bounds and local-only original-file storage are covered by the existing tests.
