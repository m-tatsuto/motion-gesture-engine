# Golden fixtures and cross-platform parity

The v0.1 golden set is a compact, synthetic regression corpus for the frozen
`LegacyGravityThresholdV1` baseline, deterministic Replay, and `motion-eval`.
It is not captured device data and must not be treated as detector-quality
evidence for real devices.

## Manifest and layout

[`fixtures/golden/manifest.json`](../fixtures/golden/manifest.json) is the
machine-readable source of truth. Each published case declares its trace path,
scenario tags, provenance, privacy tier, publication and review status,
expected output paths, and detector/version/configuration identity. The
manifest is validated by
[`golden-fixture-manifest.schema.json`](../spec/v1/schema/golden-fixture-manifest.schema.json).

The fixture set contains:

- baseline traces covering neutral input, both directions, strict trigger and
  re-arm equality, repeated gestures and samples, equal timestamps, jitter,
  shake-like noise, irregular sampling, and a long monotonic-time gap;
- committed prediction JSON Lines and complete traces containing those
  predictions;
- an evaluator trace covering duplicate and wrong-direction predictions plus
  exact and outside early/late tolerance boundaries;
- committed evaluation reports, including a trace without negative windows so
  absence of TN, specificity, and accuracy remains part of the contract;
- references to malformed and incomplete shared validation fixtures.

The evaluator-only synthetic prediction stream is declared solely so Motion
Trace records have an auditable identity. It has no detector implementation
and does not add a gesture algorithm.

Swift and Kotlin decode the same manifest and replay the same baseline traces.
Their results are compared with the same committed prediction records,
including gesture, timestamp, event sequence, source sample sequence, event ID,
and detector identity. Node validation reconstructs the complete trace contract
and compares `motion-eval` output with the committed reports. Tests only read
goldens; they never rewrite them.

## Provenance and publication safety

All records are deliberately generated synthetic values with privacy tier
`synthetic`. Gesture and negative-window source annotations use synthetic
provenance. Evaluator ground truth uses `gestureCommit` or `negativeWindow`
with `reviewedGroundTruth` provenance and references those source annotation
IDs.

The corpus contains no production or user-submitted trace, account ID, device
ID, exact model, location, or arbitrary application log. `publicApproved` means
only that the reviewed synthetic fixture is suitable for this public
repository. It is not approval to publish any future recording. Raw recordings
must never be copied into this directory or passed through the generator.

## Verification

From the repository root:

```sh
npm ci
npm test
swift test --package-path swift
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./android/gradlew -p android test
npm run golden:verify
```

The GitHub Actions `v0.1 measurement foundation gate` requires Node schema and
fixture validation, evaluator regression, Swift tests, Kotlin/Android tests,
and both native golden replays to succeed.

## Updating expected values

Expected values are frozen review artifacts. Update them only when an intended,
separately reviewed specification or frozen-baseline change requires a new
contract. Do not regenerate them to make an unexplained test failure pass.

For an intentional update:

1. Change the generator inputs or explicit contract in
   `tools/golden-fixtures/generate.mjs`.
2. Run `npm run golden:update` explicitly. This command is never called by
   tests or CI.
3. Review every source trace, prediction, complete trace, manifest, and report
   diff. Confirm the provenance and forbidden-field assertions still apply.
4. Run the complete verification commands above and explain the contract
   change in the pull request.

If only implementation code changed, leave the expected files untouched and
fix the implementation or obtain explicit approval for a contract change.
