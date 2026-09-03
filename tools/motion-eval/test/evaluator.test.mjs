import assert from "node:assert/strict";
import test from "node:test";

import {
  MotionEvaluationError,
  evaluateSessions,
  matchGestureEvents,
  mergeNegativeWindows,
  sessionFromValidatedTrace
} from "../evaluator.mjs";
import { loadValidators } from "../../validate-trace.mjs";

const REPORT_SCHEMA_ID =
  "https://raw.githubusercontent.com/m-tatsuto/motion-gesture-engine/main/spec/v1/schema/motion-evaluation-report.schema.json";

let identifierSequence = 1;

function uuid() {
  const suffix = String(identifierSequence).padStart(12, "0");
  identifierSequence += 1;
  return `00000000-0000-4000-8000-${suffix}`;
}

function truth(timestampNs, gesture = "tiltForward") {
  return {
    recordType: "annotation",
    annotationId: uuid(),
    annotationKind: "gestureCommit",
    timestampNs,
    gesture,
    provenance: {
      kind: "reviewedGroundTruth",
      reviewProtocolVersion: "review.v1",
      sourceAnnotationIds: [uuid()]
    }
  };
}

function negativeWindow(timestampNs, endTimestampNs) {
  return {
    recordType: "annotation",
    annotationId: uuid(),
    annotationKind: "negativeWindow",
    timestampNs,
    endTimestampNs,
    provenance: {
      kind: "reviewedGroundTruth",
      reviewProtocolVersion: "review.v1",
      sourceAnnotationIds: [uuid()]
    }
  };
}

function prediction(timestampNs, gesture = "tiltForward", eventSequence = 0) {
  return {
    recordType: "predictedEvent",
    eventId: uuid(),
    detectorStreamId: "detector.run",
    eventSequence,
    timestampNs,
    gesture
  };
}

function session({ annotations = [], predictions = [], version = "1.0.0", platformFamily = "ios" }) {
  return {
    detector: {
      detectorStreamId: "detector.run",
      detectorId: "TestDetector",
      detectorVersion: version,
      configurationIdentity: "test.default.v1"
    },
    platformFamily,
    annotations,
    predictions
  };
}

test("overlapping windows use deterministic maximum-cardinality matching", () => {
  const groundTruth = [truth(100), truth(200)];
  const predictions = [prediction(150, "tiltForward", 0), prediction(650, "tiltForward", 1)];
  const policy = { earlyToleranceNs: 100, lateToleranceNs: 500 };

  const first = matchGestureEvents(groundTruth, predictions, policy);
  const second = matchGestureEvents([...groundTruth].reverse(), [...predictions].reverse(), policy);

  assert.equal(first.matches.length, 2);
  assert.deepEqual(
    first.matches.map((match) => [match.groundTruth.timestampNs, match.prediction.timestampNs]),
    [
      [100, 150],
      [200, 650]
    ]
  );
  assert.deepEqual(second, first);
});

test("duplicate predictions are matched one-to-one and the remainder are false positives", () => {
  const result = matchGestureEvents(
    [truth(100)],
    [prediction(90, "tiltForward", 0), prediction(100, "tiltForward", 1)],
    { earlyToleranceNs: 20, lateToleranceNs: 20 }
  );

  assert.equal(result.matches.length, 1);
  assert.equal(result.matches[0].latencyNs, -10);
  assert.equal(result.falsePositives.length, 1);
  assert.equal(result.falseNegatives.length, 0);
});

test("wrong gestures produce one false positive and one false negative", () => {
  const report = evaluateSessions([
    session({
      annotations: [truth(1_000, "tiltForward")],
      predictions: [prediction(1_000, "tiltBackward", 0)]
    })
  ]);

  assert.deepEqual(report.overall.events, {
    truePositives: 0,
    falsePositives: 1,
    falseNegatives: 1
  });
  assert.equal(report.overall.rates.f1, 0);
  assert.deepEqual(report.byGesture.tiltForward.events, {
    truePositives: 0,
    falsePositives: 0,
    falseNegatives: 1
  });
  assert.deepEqual(report.byGesture.tiltBackward.events, {
    truePositives: 0,
    falsePositives: 1,
    falseNegatives: 0
  });
});

test("early and late boundaries are inclusive while events outside them remain unmatched", () => {
  const report = evaluateSessions(
    [
      session({
        annotations: [truth(1_000), truth(2_000)],
        predictions: [
          prediction(899, "tiltForward", 0),
          prediction(900, "tiltForward", 1),
          prediction(2_500, "tiltForward", 2),
          prediction(2_501, "tiltForward", 3)
        ]
      })
    ],
    { earlyToleranceNs: 100, lateToleranceNs: 500 }
  );

  assert.deepEqual(report.overall.events, {
    truePositives: 2,
    falsePositives: 2,
    falseNegatives: 0
  });
  assert.deepEqual(report.overall.latencyNs, {
    matchedEvents: 2,
    p50: -100,
    p95: 500
  });
});

test("only reviewed gesture commits become ground truth", () => {
  const reviewed = truth(1_000);
  const contributor = {
    ...truth(2_000),
    provenance: { kind: "contributor", collectionProtocolVersion: "collection.v1" }
  };
  const synthetic = {
    ...truth(3_000),
    provenance: {
      kind: "synthetic",
      generatorId: "test.fixture",
      generatorVersion: "1.0.0"
    }
  };
  const userReport = {
    recordType: "annotation",
    annotationId: uuid(),
    annotationKind: "userReportedProblem",
    timestampNs: 4_000,
    provenance: { kind: "userReport", collectionProtocolVersion: "feedback.v1" },
    report: { problemCode: "missedGesture", expectedGesture: "tiltForward" }
  };

  const report = evaluateSessions([
    session({
      annotations: [reviewed, contributor, synthetic, userReport],
      predictions: [prediction(1_000)]
    })
  ]);

  assert.deepEqual(report.overall.events, {
    truePositives: 1,
    falsePositives: 0,
    falseNegatives: 0
  });
  assert.deepEqual(report.dataset.annotationSelection.ignoredByProvenance, {
    synthetic: 1,
    contributor: 1,
    userReport: 1
  });
});

test("negative windows are unioned for exposure and provide window-level classification", () => {
  const report = evaluateSessions(
    [
      session({
        annotations: [
          truth(500),
          negativeWindow(0, 100),
          negativeWindow(50, 200),
          negativeWindow(300, 400)
        ],
        predictions: [
          prediction(150, "tiltForward", 0),
          prediction(450, "tiltForward", 1),
          prediction(500, "tiltForward", 2)
        ]
      })
    ],
    { earlyToleranceNs: 0, lateToleranceNs: 0 }
  );

  assert.deepEqual(report.overall.events, {
    truePositives: 1,
    falsePositives: 2,
    falseNegatives: 0
  });
  assert.deepEqual(report.overall.negativeExposure, {
    durationNs: 300,
    falsePositives: 1
  });
  assert.equal(report.overall.rates.falsePositivesPerNegativeHour, 12_000_000_000);
  assert.deepEqual(report.overall.negativeWindowClassification, {
    windows: 2,
    trueNegatives: 1,
    falsePositiveWindows: 1,
    specificity: 0.5,
    accuracy: 2 / 3
  });
});

test("TN, specificity, and accuracy are absent without reviewed negative windows", () => {
  const report = evaluateSessions([
    session({ annotations: [truth(100)], predictions: [prediction(100)] })
  ]);

  assert.equal("negativeWindowClassification" in report.overall, false);
  assert.equal(report.overall.rates.falsePositivesPerNegativeHour, null);
});

test("half-open negative windows merge overlaps and touching boundaries", () => {
  assert.deepEqual(
    mergeNegativeWindows([
      { startTimestampNs: 20, endTimestampNs: 30 },
      { startTimestampNs: 0, endTimestampNs: 10 },
      { startTimestampNs: 5, endTimestampNs: 25 },
      { startTimestampNs: 30, endTimestampNs: 40 }
    ]),
    [{ startTimestampNs: 0, endTimestampNs: 40 }]
  );
});

test("grouping is opt-in and uses only supported declared dimensions", () => {
  const report = evaluateSessions(
    [
      session({ annotations: [truth(100)], predictions: [prediction(100)], version: "1.0.0" }),
      session({
        annotations: [truth(200)],
        predictions: [],
        version: "2.0.0",
        platformFamily: "android"
      })
    ],
    { groupBy: ["detectorVersion", "platformFamily"] }
  );

  assert.equal(report.groups.length, 2);
  assert.deepEqual(
    report.groups.map((group) => group.dimensions),
    [
      { detectorVersion: "1.0.0", platformFamily: "ios" },
      { detectorVersion: "2.0.0", platformFamily: "android" }
    ]
  );
  assert.deepEqual(report.overall.events, {
    truePositives: 1,
    falsePositives: 0,
    falseNegatives: 1
  });
});

test("regression gates fail closed when a requested metric is unavailable", () => {
  const report = evaluateSessions(
    [session({ predictions: [prediction(100)] })],
    {
      gates: {
        minRecall: 0.5,
        maxFalsePositivesPerNegativeHour: 1
      }
    }
  );

  assert.equal(report.gates.passed, false);
  assert.equal(report.gates.checks[0].actual, null);
  assert.equal(report.gates.checks[0].reason, "metricUnavailable");
  assert.equal(report.gates.checks[1].actual, null);
});

test("regression gates compare available overall metrics", () => {
  const report = evaluateSessions(
    [
      session({
        annotations: [truth(100)],
        predictions: [prediction(100, "tiltForward", 0), prediction(101, "tiltForward", 1)]
      })
    ],
    {
      gates: {
        minPrecision: 0.75,
        minRecall: 1,
        maxFalsePositivesPerSession: 1
      }
    }
  );

  assert.equal(report.overall.rates.precision, 0.5);
  assert.deepEqual(
    report.gates.checks.map((check) => [check.name, check.passed]),
    [
      ["minPrecision", false],
      ["minRecall", true],
      ["maxFalsePositivesPerSession", true]
    ]
  );
  assert.equal(report.gates.passed, false);
});

test("invalid evaluator configuration has stable error codes", () => {
  assert.throws(
    () => evaluateSessions([session({})], { earlyToleranceNs: -1 }),
    (error) => error instanceof MotionEvaluationError && error.code === "invalidConfiguration"
  );
  assert.throws(
    () => evaluateSessions([session({})], { groupBy: ["exactModel"] }),
    (error) => error instanceof MotionEvaluationError && error.code === "invalidGroupDimension"
  );
  assert.throws(
    () =>
      evaluateSessions([
        session({ annotations: [negativeWindow(100, 100)] })
      ]),
    (error) => error instanceof MotionEvaluationError && error.code === "invalidNegativeWindow"
  );
  assert.throws(
    () => evaluateSessions([session({})], { gates: { minPrecision: 1.1 } }),
    (error) => error instanceof MotionEvaluationError && error.code === "invalidGate"
  );
});

test("aggregate nanosecond exposure fails instead of losing integer precision", () => {
  const veryLongWindow = negativeWindow(0, Number.MAX_SAFE_INTEGER);
  assert.throws(
    () =>
      evaluateSessions([
        session({ annotations: [veryLongWindow] }),
        session({ annotations: [veryLongWindow] })
      ]),
    (error) => error instanceof MotionEvaluationError && error.code === "aggregateOverflow"
  );
});

test("generated JSON conforms to the closed evaluation report schema", async () => {
  const report = evaluateSessions(
    [
      session({
        annotations: [truth(100), negativeWindow(200, 300)],
        predictions: [prediction(100)],
        platformFamily: "android"
      })
    ],
    {
      groupBy: ["detectorVersion", "platformFamily"],
      gates: { minF1: 0.9, maxP95LatencyMs: 100 }
    }
  );
  const { ajv } = await loadValidators();
  const validate = ajv.getSchema(REPORT_SCHEMA_ID);

  assert.ok(validate, "evaluation report schema was not compiled");
  assert.equal(validate(report), true, JSON.stringify(validate.errors));
});

test("detector selection is explicit for traces with multiple streams", () => {
  const detectorA = {
    detectorStreamId: "detector.a",
    detectorId: "TestDetector",
    detectorVersion: "1.0.0",
    configurationIdentity: "test.a"
  };
  const detectorB = {
    detectorStreamId: "detector.b",
    detectorId: "TestDetector",
    detectorVersion: "2.0.0",
    configurationIdentity: "test.b"
  };
  const validation = {
    traceState: "finalizedComplete",
    records: [
      {
        recordType: "traceHeader",
        detectors: [detectorA, detectorB],
        device: { platformFamily: "ios" }
      },
      { ...prediction(100), detectorStreamId: "detector.a" },
      { ...prediction(200), detectorStreamId: "detector.b" }
    ]
  };

  assert.throws(
    () => sessionFromValidatedTrace(validation),
    (error) => error instanceof MotionEvaluationError && error.code === "ambiguousDetector"
  );
  const selected = sessionFromValidatedTrace(validation, "detector.b");
  assert.equal(selected.detector.detectorVersion, "2.0.0");
  assert.equal(selected.predictions.length, 1);
  assert.equal(selected.predictions[0].timestampNs, 200);
});

test("aggregate reports are invariant to session and record input order", () => {
  const firstSession = session({
    annotations: [truth(200), truth(100)],
    predictions: [prediction(200, "tiltForward", 1), prediction(100, "tiltForward", 0)],
    version: "2.0.0",
    platformFamily: "android"
  });
  const secondSession = session({
    annotations: [truth(300)],
    predictions: [],
    version: "1.0.0",
    platformFamily: "ios"
  });
  const options = { groupBy: ["detectorVersion", "platformFamily"] };

  const forward = evaluateSessions([firstSession, secondSession], options);
  const reversed = evaluateSessions(
    [
      secondSession,
      {
        ...firstSession,
        annotations: [...firstSession.annotations].reverse(),
        predictions: [...firstSession.predictions].reverse()
      }
    ],
    options
  );

  assert.deepEqual(reversed, forward);
});
