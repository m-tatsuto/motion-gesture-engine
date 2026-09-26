#!/usr/bin/env node

import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { evaluateSessions } from "../motion-eval/evaluator.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const GOLDEN = path.join(ROOT, "fixtures", "golden");
const DETECTOR = {
  detectorStreamId: "legacy.replay.v1",
  detectorId: "LegacyGravityThresholdV1",
  detectorVersion: "1.0.0",
  configurationIdentity: "legacy.default.v1"
};
const EVALUATOR_DETECTOR = {
  detectorStreamId: "golden.evaluator.v1",
  detectorId: "SyntheticPredictionFixture",
  detectorVersion: "1.0.0",
  configurationIdentity: "evaluator.boundaries.v1"
};

function uuid(group, suffix) {
  return `${group}0000000-0000-4000-8000-${String(suffix).padStart(12, "0")}`;
}

function header(traceId, detector, maximumDurationNs, maximumSamples) {
  return {
    recordType: "traceHeader",
    schemaVersion: "1.0.0-draft.1",
    coreSpecVersion: "1.0.0-draft.1",
    traceId,
    producer: {
      libraryName: "motionGestureCore",
      libraryVersion: "0.1.0",
      platformAdapterName: "syntheticGoldenFixture",
      platformAdapterVersion: "1.0.0"
    },
    privacy: {
      tier: "synthetic",
      dataClasses: ["motionSensorData", "gestureAnnotation"]
    },
    conventions: {
      storedVectorFrame: "deviceD",
      gravityUnit: "standardGravity",
      userAccelerationUnit: "standardGravity",
      rotationRateUnit: "radianPerSecond",
      attitudeQuaternion: "xyzwReferenceFromDevice",
      attitudeQuaternionNormTolerance: 0.001,
      frameOrthonormalTolerance: 0.000001,
      standardGravityMps2: 9.80665,
      timestampUnit: "nanosecond",
      timestampOrigin: "sessionMonotonicOrigin",
      sampleOrdering: "timestampThenSequence"
    },
    orderingPolicy: { sampleReordering: { kind: "none" } },
    recorderLimits: {
      maximumDurationNs,
      maximumSamples,
      maximumBytes: 1048576
    },
    session: {
      displayRotationClockwiseAtStart: 0,
      gestureFrameFromDeviceRowMajor: [1, 0, 0, 0, 0, -1, 0, 1, 0],
      gestureFrameFrozen: true
    },
    capabilities: [
      {
        capabilityId: "gravity.main",
        signalKind: "gravity",
        requirement: "required",
        biasCorrection: "notApplicable",
        availability: "available",
        sourceKind: "fused",
        nativeSourceIdentifier: "synthetic.gravity",
        nativeUnit: "standardGravity",
        nativeSignConvention: "physicalGravity",
        conversions: ["none"]
      }
    ],
    detectors: [detector]
  };
}

function sample(timestampNs, sequence, gravityZG) {
  return {
    recordType: "sample",
    timestampNs,
    sequence,
    signals: {
      gravity: { capabilityId: "gravity.main", value: [0, gravityZG, 0] }
    }
  };
}

function syntheticAnnotation(annotationId, annotationKind, timestampNs, extras = {}) {
  return {
    recordType: "annotation",
    annotationId,
    annotationKind,
    timestampNs,
    ...extras,
    provenance: {
      kind: "synthetic",
      generatorId: "golden.fixture.generator",
      generatorVersion: "1.0.0"
    }
  };
}

function reviewedAnnotation(annotationId, sourceAnnotationId, annotationKind, timestampNs, extras = {}) {
  return {
    recordType: "annotation",
    annotationId,
    annotationKind,
    timestampNs,
    ...extras,
    provenance: {
      kind: "reviewedGroundTruth",
      reviewProtocolVersion: "synthetic.review.v1",
      sourceAnnotationIds: [sourceAnnotationId]
    }
  };
}

function gestureAnnotations(group, startSuffix, timestampNs, gesture) {
  const sourceId = uuid(group, startSuffix);
  return [
    syntheticAnnotation(sourceId, "gestureIntent", timestampNs, { gesture }),
    reviewedAnnotation(uuid(group, startSuffix + 1), sourceId, "gestureCommit", timestampNs, {
      gesture
    })
  ];
}

function negativeAnnotations(group, startSuffix, timestampNs, endTimestampNs) {
  const sourceId = uuid(group, startSuffix);
  return [
    syntheticAnnotation(sourceId, "neutralInterval", timestampNs, { endTimestampNs }),
    reviewedAnnotation(uuid(group, startSuffix + 1), sourceId, "negativeWindow", timestampNs, {
      endTimestampNs
    })
  ];
}

function footer(samples, annotations, predictions, durationNs) {
  const intervals = samples.slice(1).map((entry, index) => entry.timestampNs - samples[index].timestampNs);
  return {
    recordType: "traceFooter",
    schemaVersion: "1.0.0-draft.1",
    finalizationStatus: "complete",
    terminationReason: "requestedStop",
    durationNs,
    recordCounts: {
      samples: samples.length,
      annotations: annotations.length,
      predictedEvents: predictions.length,
      displayRotationChanges: 0,
      capabilityChanges: 0
    },
    reorderedSamples: 0,
    droppedSamples: { total: 0, byReason: [] },
    observedTiming: [
      {
        capabilityId: "gravity.main",
        acceptedObservationCount: samples.length,
        ...(intervals.length > 0
          ? {
              minimumIntervalNs: Math.min(...intervals),
              maximumIntervalNs: Math.max(...intervals)
            }
          : {})
      }
    ]
  };
}

function fnv1a(value) {
  let hash = 0xcbf29ce484222325n;
  for (const byte of Buffer.from(value, "utf8")) {
    hash ^= BigInt(byte);
    hash = BigInt.asUintN(64, hash * 0x100000001b3n);
  }
  return hash;
}

function eventId(traceId, detectorStreamId, eventSequence) {
  const seed = `${traceId}|${detectorStreamId}|${eventSequence}`;
  const bytes = Buffer.alloc(16);
  bytes.writeBigUInt64BE(fnv1a(`mge.replay.event.v1.a|${seed}`), 0);
  bytes.writeBigUInt64BE(fnv1a(`mge.replay.event.v1.b|${seed}`), 8);
  bytes[6] = (bytes[6] & 0x0f) | 0x80;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function baselinePredictions(traceId, samples) {
  let armed = true;
  const predictions = [];
  for (const entry of samples) {
    const gravityZG = entry.signals.gravity.value[1];
    let gesture;
    if (armed && gravityZG > 0.65) gesture = "tiltForward";
    if (armed && gravityZG < -0.65) gesture = "tiltBackward";
    if (gesture) {
      const eventSequence = predictions.length;
      predictions.push({
        recordType: "predictedEvent",
        eventId: eventId(traceId, DETECTOR.detectorStreamId, eventSequence),
        detectorStreamId: DETECTOR.detectorStreamId,
        eventSequence,
        timestampNs: entry.timestampNs,
        gesture,
        sourceSampleSequence: entry.sequence
      });
      armed = false;
    } else if (!armed && Math.abs(gravityZG) < 0.35) {
      armed = true;
    }
  }
  return predictions;
}

function recordsFor(definition, predictions = []) {
  return [
    header(
      definition.traceId,
      definition.detector,
      definition.durationNs || 1,
      Math.max(definition.samples.length, 1)
    ),
    ...definition.samples,
    ...definition.annotations,
    ...predictions,
    footer(definition.samples, definition.annotations, predictions, definition.durationNs)
  ];
}

function jsonLines(records) {
  return `${records.map((record) => JSON.stringify(record)).join("\n")}\n`;
}

function predictionJsonLines(records) {
  return records.length === 0 ? "" : jsonLines(records);
}

function session(definition, predictions) {
  return {
    detector: definition.detector,
    platformFamily: "undeclared",
    annotations: definition.annotations,
    predictions
  };
}

const baselineDefinitions = [
  {
    id: "neutral-boundaries-noise",
    traceId: "40000000-0000-4000-8000-000000000001",
    detector: DETECTOR,
    scenarioTags: [
      "neutral",
      "thresholdEquality",
      "nearThresholdJitter",
      "shakeLikeNoise"
    ],
    samples: [
      sample(0, 0, 0),
      sample(10_000_000, 1, 0.65),
      sample(20_000_000, 2, -0.65),
      sample(30_000_000, 3, 0.649),
      sample(40_000_000, 4, -0.649),
      sample(50_000_000, 5, 0.2),
      sample(60_000_000, 6, -0.2),
      sample(70_000_000, 7, 0.64),
      sample(80_000_000, 8, -0.64),
      sample(90_000_000, 9, 0)
    ],
    annotations: negativeAnnotations("5", 1, 0, 100_000_000),
    durationNs: 100_000_000
  },
  {
    id: "forward-rearm-repeat",
    traceId: "40000000-0000-4000-8000-000000000002",
    detector: DETECTOR,
    scenarioTags: [
      "tiltForward",
      "rearmBoundary",
      "repeatedGestures",
      "repeatedSamples",
      "equalTimestampSamples"
    ],
    samples: [
      sample(0, 0, 0.66),
      sample(10_000_000, 1, 0.8),
      sample(20_000_000, 2, 0.35),
      sample(30_000_000, 3, 0.34),
      sample(40_000_000, 4, 0.66),
      sample(40_000_000, 6, 0.66),
      sample(50_000_000, 9, 0.34)
    ],
    annotations: [
      ...gestureAnnotations("6", 1, 0, "tiltForward"),
      ...gestureAnnotations("6", 3, 40_000_000, "tiltForward")
    ],
    durationNs: 50_000_000
  },
  {
    id: "backward-irregular-long-gap",
    traceId: "40000000-0000-4000-8000-000000000003",
    detector: DETECTOR,
    scenarioTags: [
      "tiltBackward",
      "repeatedGestures",
      "irregularSampling",
      "longTimingGap"
    ],
    samples: [
      sample(0, 0, -0.66),
      sample(7_000_000, 2, -0.8),
      sample(21_000_000, 5, -0.34),
      sample(1_000_000_000, 8, -0.66),
      sample(9_000_000_000_000, 13, 0)
    ],
    annotations: [
      ...gestureAnnotations("7", 1, 0, "tiltBackward"),
      ...gestureAnnotations("7", 3, 1_000_000_000, "tiltBackward")
    ],
    durationNs: 9_000_000_000_000
  }
];

const adversarialDefinition = {
  id: "evaluator-boundaries",
  traceId: "40000000-0000-4000-8000-000000000004",
  detector: EVALUATOR_DETECTOR,
  scenarioTags: [
    "duplicatePrediction",
    "wrongDirection",
    "earlyBoundary",
    "lateBoundary",
    "outsideTolerance",
    "negativeExposure"
  ],
  samples: [sample(0, 0, 0)],
  annotations: [
    ...gestureAnnotations("8", 1, 100_000_000, "tiltForward"),
    ...gestureAnnotations("8", 3, 1_000_000_000, "tiltBackward"),
    ...gestureAnnotations("8", 5, 2_000_000_000, "tiltForward"),
    ...gestureAnnotations("8", 7, 3_000_000_000, "tiltForward"),
    ...negativeAnnotations("8", 9, 0, 200_000_000),
    ...negativeAnnotations("8", 11, 1_700_000_000, 1_900_000_000),
    ...negativeAnnotations("8", 13, 4_000_000_000, 3_603_600_000_000)
  ],
  durationNs: 3_603_600_000_000
};

const adversarialPredictions = [
  [0, "tiltForward"],
  [100_000_000, "tiltForward"],
  [1_500_000_000, "tiltBackward"],
  [1_899_000_000, "tiltForward"],
  [2_501_000_000, "tiltForward"],
  [3_000_000_000, "tiltBackward"]
].map(([timestampNs, gesture], eventSequence) => ({
  recordType: "predictedEvent",
  eventId: uuid("9", eventSequence + 1),
  detectorStreamId: EVALUATOR_DETECTOR.detectorStreamId,
  eventSequence,
  timestampNs,
  gesture
}));

await mkdir(path.join(GOLDEN, "traces"), { recursive: true });
await mkdir(path.join(GOLDEN, "expected"), { recursive: true });

const manifestCases = [];
for (const definition of baselineDefinitions) {
  const predictions = baselinePredictions(definition.traceId, definition.samples);
  const tracePath = `traces/${definition.id}.mge.jsonl`;
  const predictionPath = `expected/${definition.id}.predictions.jsonl`;
  const fullTracePath = `expected/${definition.id}.with-predictions.mge.jsonl`;
  const reportPath = `expected/${definition.id}.evaluation-report.json`;
  await writeFile(path.join(GOLDEN, tracePath), jsonLines(recordsFor(definition)), "utf8");
  await writeFile(path.join(GOLDEN, predictionPath), predictionJsonLines(predictions), "utf8");
  await writeFile(
    path.join(GOLDEN, fullTracePath),
    jsonLines(recordsFor(definition, predictions)),
    "utf8"
  );
  await writeFile(
    path.join(GOLDEN, reportPath),
    `${JSON.stringify(evaluateSessions([session(definition, predictions)]), null, 2)}\n`,
    "utf8"
  );
  manifestCases.push({
    id: definition.id,
    tracePath,
    scenarioTags: definition.scenarioTags,
    provenance: {
      kind: "synthetic",
      generatorId: "golden.fixture.generator",
      generatorVersion: "1.0.0"
    },
    privacyTier: "synthetic",
    publicationStatus: "publicApproved",
    reviewStatus: "reviewedSynthetic",
    expectedPredictionPath: predictionPath,
    replayTraceWithPredictionsPath: fullTracePath,
    expectedEvaluationReportPath: reportPath,
    detector: definition.detector
  });
}

const adversarialTracePath = "traces/evaluator-boundaries.with-predictions.mge.jsonl";
const adversarialReportPath = "expected/evaluator-boundaries.evaluation-report.json";
await writeFile(
  path.join(GOLDEN, adversarialTracePath),
  jsonLines(recordsFor(adversarialDefinition, adversarialPredictions)),
  "utf8"
);
await writeFile(
  path.join(GOLDEN, adversarialReportPath),
  `${JSON.stringify(
    evaluateSessions([session(adversarialDefinition, adversarialPredictions)]),
    null,
    2
  )}\n`,
  "utf8"
);

const manifest = {
  manifestVersion: "1.0.0",
  fixtureSet: "v0.1-measurement-foundation",
  dataPolicy: {
    syntheticOnly: true,
    containsProductionData: false,
    containsUserSubmittedData: false,
    forbiddenFields: [
      "accountId",
      "deviceId",
      "exactModel",
      "location",
      "applicationLogs",
      "arbitraryLog"
    ]
  },
  baselineCases: manifestCases,
  evaluatorCases: [
    {
      id: adversarialDefinition.id,
      tracePath: adversarialTracePath,
      scenarioTags: adversarialDefinition.scenarioTags,
      provenance: {
        kind: "synthetic",
        generatorId: "golden.fixture.generator",
        generatorVersion: "1.0.0"
      },
      privacyTier: "synthetic",
      publicationStatus: "publicApproved",
      reviewStatus: "reviewedSynthetic",
      expectedEvaluationReportPath: adversarialReportPath,
      detector: adversarialDefinition.detector
    }
  ],
  replayErrorCases: [
    {
      id: "malformed-sample",
      tracePath: "../v1/invalid/sample-without-signals.mge.jsonl",
      scenarioTags: ["malformedTrace"],
      expectedSchemaErrorCode: "schemaViolation",
      expectedReplayErrorCode: "malformedRecord"
    },
    {
      id: "incomplete-trace",
      tracePath: "../v1/invalid/missing-footer.mge.jsonl",
      scenarioTags: ["incompleteTrace"],
      expectedSchemaErrorCode: "incompleteTrace",
      expectedReplayErrorCode: "incompleteTrace"
    }
  ]
};

await writeFile(path.join(GOLDEN, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
console.log(`Wrote ${manifestCases.length + 1} golden traces and expected outputs.`);
