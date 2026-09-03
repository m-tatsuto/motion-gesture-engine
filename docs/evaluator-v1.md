# Event evaluator and `motion-eval` v1

| Field | Value |
| --- | --- |
| Report version | `1.0.0-draft.1` |
| Evaluator | `motion-eval` `0.1.0` |
| Default early tolerance | 100 ms |
| Default late tolerance | 500 ms |

`motion-eval` performs deterministic, event-based evaluation over finalized-complete Motion Trace v1 files. It emits aggregate JSON for CI and Markdown for review. It does not read sensors, run a detector, upload data, use wall-clock time, or map generic gestures to application actions.

## Input and detector selection

Every input MUST pass the Motion Trace schema and semantic validator and MUST have a `complete` or `bounded` footer. Both `.mge.jsonl` and `.mge.jsonl.gz` are accepted under the validator's resource limits.

Evaluation selects exactly one declared detector stream from each trace. When a trace declares one stream, that stream is selected automatically. A caller MUST pass `--detector-stream` when a trace declares more than one stream. Other prediction streams are ignored rather than combined.

Each accepted trace is one session for aggregation. A session need not contain a prediction or a reviewed gesture event.

## Ground truth is reviewed annotation, not user feedback

The positive event anchor is an annotation with both:

- `annotationKind: "gestureCommit"`; and
- `provenance.kind: "reviewedGroundTruth"`.

Its `timestampNs` is the latency anchor and its `gesture` is the required class. A contributor annotation, synthetic source annotation, or `userReportedProblem` is never promoted implicitly to ground truth. Their aggregate counts remain visible under `dataset.annotationSelection`, without their identifiers or contents.

A negative exposure interval similarly requires `annotationKind: "negativeWindow"` and `provenance.kind: "reviewedGroundTruth"`. It MUST have positive duration for evaluation. Other neutral or negative-looking annotations are not exposure denominators.

## One-to-one matching

Matching is performed independently for each generic gesture. A prediction for the wrong gesture cannot match, even when its timestamp is otherwise eligible.

For a ground-truth commit at `t`, a prediction at `p` is eligible when both ends of this interval are satisfied:

```text
t - earlyToleranceNs <= p <= t + lateToleranceNs
```

The defaults are 100 ms early and 500 ms late. CLI values are converted to the nearest integer nanosecond. Tolerances MUST be non-negative safe integers.

Within each gesture, ground truth is sorted by `(timestampNs, annotationId)` and predictions by `(timestampNs, eventSequence, eventId)`. Predictions are then scanned in that order. Each prediction is assigned to the eligible unmatched ground-truth window whose deadline occurs first. Because every event uses the same early and late tolerances, this earliest-deadline rule produces maximum-cardinality one-to-one matching without quadratic search. Stable sort keys make the selected assignment deterministic when timestamps are equal or windows overlap.

After matching:

- every pair is one true positive (`TP`);
- every unmatched prediction is one false positive (`FP`); and
- every unmatched reviewed gesture commit is one false negative (`FN`).

Duplicate predictions therefore cannot claim the same reviewed event. A wrong-gesture prediction produces an FP for its predicted gesture while the reviewed event remains an FN for its gesture.

## Latency

Matched-event latency is signed:

```text
latencyNs = prediction.timestampNs - gestureCommit.timestampNs
```

A negative value is early and a positive value is late. P50 and p95 use the nearest-rank definition over matched events sorted by signed latency. A percentile is JSON `null` and Markdown `—` when there are no matched events.

## Event metrics and aggregation

Counts are summed first and rates are derived from those sums (micro aggregation), both overall and for every v1 gesture:

```text
precision = TP / (TP + FP)
recall    = TP / (TP + FN)
F1        = 2TP / (2TP + FP + FN)
FP/session = FP / number of input traces
```

A zero denominator is reported as JSON `null`, except F1 is `0` when any FP or FN makes its count-based denominator non-zero. Session rates are never averaged, so a small session does not receive the same weight as a large one for precision, recall, or F1.

## Declared negative exposure

Reviewed negative windows use half-open intervals `[timestampNs, endTimestampNs)`. Overlapping and touching intervals are merged within each session before exposure is counted. Intervals are never merged across sessions.

```text
FP/negative hour = unmatched predictions inside merged windows
                   / (merged duration in hours)
```

The numerator counts each unmatched prediction at most once. The rate is `null` when no positive-duration reviewed negative exposure exists. Per-gesture rates use false positives for that gesture over the same declared exposure.

True-negative classification is a separate, secondary view. Each merged negative window is one explicit negative opportunity:

- a window containing no unmatched prediction is one true negative;
- a window containing at least one unmatched prediction is one false-positive window;
- `specificity = trueNegativeWindows / mergedNegativeWindows`; and
- `accuracy = (TP + trueNegativeWindows) / (reviewedGestureCommits + mergedNegativeWindows)`.

The JSON `negativeWindowClassification` object and the corresponding Markdown metrics are omitted entirely when no reviewed negative window exists. Window-level accuracy does not count every sensor sample and MUST NOT replace event precision, recall, F1, or FP/exposure. Event FPs outside declared negative windows remain in the primary event metrics even though they cannot be assigned to a negative-window opportunity.

## Grouping

Grouping is opt-in with repeatable or comma-separated `--group-by` values. V1 permits only:

- `detectorVersion`; and
- `platformFamily` (`ios`, `android`, `other`, or `undeclared`).

Every group recomputes micro-aggregated metrics from its sessions. Exact model, OS major version, trace identifier, event identifier, annotation identifier, and input filename are not grouping dimensions.

## Privacy-safe reports

Default JSON and Markdown reports contain aggregate metrics, technical detector identity/version/configuration, matching policy, provenance counts, optional allowed groups, and gate results. JSON conforms to the closed [`motion-evaluation-report.schema.json`](../spec/v1/schema/motion-evaluation-report.schema.json) contract. Reports do not contain:

- input paths or filenames;
- trace, event, annotation, or source-sample identifiers;
- exact device model or OS version;
- sensor samples;
- user-report problem contents; or
- arbitrary producer metadata.

Validation failures identify inputs by one-based ordinal. Selecting `platformFamily` explicitly exposes only that declared coarse family in group labels.

## CLI

From the repository root:

```sh
npm run motion-eval -- fixtures/v1/valid/full.mge.jsonl
```

Markdown is written to stdout when no output option is supplied. JSON and Markdown may be produced together:

```sh
npm run motion-eval -- \
  --json evaluation-output/report.json \
  --markdown evaluation-output/report.md \
  --group-by detectorVersion,platformFamily \
  trace-a.mge.jsonl trace-b.mge.jsonl.gz
```

Use `-` as one output target to write that format to stdout. File reports are replaced atomically after their parent directory is created. JSON and Markdown cannot both target stdout or the same file, and an output path cannot replace an input trace.

## CI regression gates

Available gates are:

```text
--min-precision <0..1>
--min-recall <0..1>
--min-f1 <0..1>
--max-fp-per-session <number>
--max-fp-per-negative-hour <number>
--max-p95-latency-ms <ms>
```

Gates evaluate overall metrics. A configured gate fails closed when its metric is unavailable; for example, an FP/negative-hour gate fails when the inputs declare no reviewed negative exposure. Reports are written before the process exits.

| Exit | Meaning |
| --- | --- |
| `0` | Evaluation completed and every configured gate passed |
| `2` | Arguments, input validation, or evaluation failed |
| `3` | Evaluation completed but at least one regression gate failed |

The evaluator tests fix duplicate prediction, overlapping window, wrong gesture, inclusive early/late boundary, annotation provenance, negative exposure, grouping, output redaction, gzip input, and unavailable-gate behavior.
