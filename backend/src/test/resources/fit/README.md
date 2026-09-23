# FIT fixture

- Filename: `20053968686_ACTIVITY.fit`
- Source: repository path `examples/20053968686_ACTIVITY.fit`
- Redistribution: explicitly approved by the user for committing as a test resource on 2026-09-21.
- SHA-256: `fe0710d14f164f033948d3f499ee72886fbf1f11c5193540459cbd37a1d39a67`
- Semantics: one running session starting at `2025-08-14T06:03:00Z`, 3,578 records, 11 laps, precise GPS, a zero cadence value, and nullable altitude. It is the offline regression fixture for summary, UTC, GPS, lap, and absent-sensor persistence.
- Re-export case: tests use different in-process bytes with the same deterministic decoded session model to prove identity/start-time de-duplication; no network fixture is downloaded.
- Corrupt case: tests supply a three-byte in-process payload and assert a safe terminal outcome with no neighbor rollback or durable original.
- Non-running and multi-session case: the decoder classifies sport before persistence and rejects any decoded input with other than exactly one session. These cases remain deterministic decoded-model seams because their Garmin binary encodings are device/profile dependent; neither needs external data or analytics.
- Coordinate and DST boundaries: `GarminFitFixtureAdapterTests` covers semicircle conversion including invalid coordinates, while `ImportControllerTests` asserts the canonical UTC instant without local-time conversion.
- Storage boundary: import tests assert normalized PostgreSQL rows only (no `bytea` columns) and verify that successful raw bytes live exclusively below the configured local data directory.
