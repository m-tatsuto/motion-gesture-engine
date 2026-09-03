export const MOTION_EVAL_VERSION = "0.1.0";
export const REPORT_VERSION = "1.0.0-draft.1";
export const GESTURES = Object.freeze(["tiltForward", "tiltBackward"]);
export const GROUP_DIMENSIONS = Object.freeze(["detectorVersion", "platformFamily"]);

export const DEFAULT_MATCHING_POLICY = Object.freeze({
  earlyToleranceNs: 100_000_000,
  lateToleranceNs: 500_000_000
});

const HOUR_NS = 3_600_000_000_000;
const PROVENANCE_KINDS = ["synthetic", "contributor", "userReport"];

export class MotionEvaluationError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "MotionEvaluationError";
    this.code = code;
  }
}

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function compareGroundTruth(left, right) {
  return (
    left.timestampNs - right.timestampNs || compareText(left.annotationId, right.annotationId)
  );
}

function comparePrediction(left, right) {
  return (
    left.timestampNs - right.timestampNs ||
    left.eventSequence - right.eventSequence ||
    compareText(left.eventId, right.eventId)
  );
}

function assertSafeNonNegativeInteger(value, name) {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new MotionEvaluationError(
      "invalidConfiguration",
      `${name} must be a non-negative safe integer`
    );
  }
}

function assertRate(value, name) {
  if (value === undefined) {
    return;
  }
  if (!Number.isFinite(value) || value < 0 || value > 1) {
    throw new MotionEvaluationError("invalidGate", `${name} must be between 0 and 1`);
  }
}

function assertNonNegative(value, name) {
  if (value === undefined) {
    return;
  }
  if (!Number.isFinite(value) || value < 0) {
    throw new MotionEvaluationError("invalidGate", `${name} must be non-negative`);
  }
}

function emptyAnnotationSelection() {
  return {
    reviewedGestureCommits: 0,
    reviewedNegativeWindows: 0,
    ignoredReviewedAnnotations: 0,
    ignoredByProvenance: {
      synthetic: 0,
      contributor: 0,
      userReport: 0
    }
  };
}

function addAnnotationSelection(target, source) {
  target.reviewedGestureCommits += source.reviewedGestureCommits;
  target.reviewedNegativeWindows += source.reviewedNegativeWindows;
  target.ignoredReviewedAnnotations += source.ignoredReviewedAnnotations;
  for (const kind of PROVENANCE_KINDS) {
    target.ignoredByProvenance[kind] += source.ignoredByProvenance[kind];
  }
}

function selectAnnotations(annotations) {
  const selection = emptyAnnotationSelection();
  const groundTruth = [];
  const negativeWindows = [];

  for (const annotation of annotations) {
    if (annotation.provenance.kind !== "reviewedGroundTruth") {
      selection.ignoredByProvenance[annotation.provenance.kind] += 1;
      continue;
    }

    if (annotation.annotationKind === "gestureCommit") {
      selection.reviewedGestureCommits += 1;
      groundTruth.push(annotation);
      continue;
    }

    if (annotation.annotationKind === "negativeWindow") {
      if (annotation.endTimestampNs <= annotation.timestampNs) {
        throw new MotionEvaluationError(
          "invalidNegativeWindow",
          "Reviewed negative windows must have positive duration"
        );
      }
      selection.reviewedNegativeWindows += 1;
      negativeWindows.push({
        startTimestampNs: annotation.timestampNs,
        endTimestampNs: annotation.endTimestampNs
      });
      continue;
    }

    selection.ignoredReviewedAnnotations += 1;
  }

  return { selection, groundTruth, negativeWindows };
}

/**
 * Matches one gesture stream with a deterministic earliest-deadline scan.
 * All ground-truth windows use the same early/late tolerance, so consuming the
 * earliest-ending eligible window maximizes cardinality without quadratic work.
 */
export function matchGestureEvents(groundTruthInput, predictionInput, policy) {
  const groundTruth = [...groundTruthInput].sort(compareGroundTruth);
  const predictions = [...predictionInput].sort(comparePrediction);
  const matches = [];
  const falsePositives = [];
  const falseNegatives = [];
  let nextGroundTruth = 0;

  for (const prediction of predictions) {
    while (
      nextGroundTruth < groundTruth.length &&
      prediction.timestampNs - groundTruth[nextGroundTruth].timestampNs >
        policy.lateToleranceNs
    ) {
      falseNegatives.push(groundTruth[nextGroundTruth]);
      nextGroundTruth += 1;
    }

    if (
      nextGroundTruth < groundTruth.length &&
      groundTruth[nextGroundTruth].timestampNs - prediction.timestampNs <=
        policy.earlyToleranceNs
    ) {
      const truth = groundTruth[nextGroundTruth];
      nextGroundTruth += 1;
      matches.push({
        groundTruth: truth,
        prediction,
        latencyNs: prediction.timestampNs - truth.timestampNs
      });
    } else {
      falsePositives.push(prediction);
    }
  }

  while (nextGroundTruth < groundTruth.length) {
    falseNegatives.push(groundTruth[nextGroundTruth]);
    nextGroundTruth += 1;
  }

  return { matches, falsePositives, falseNegatives };
}

export function mergeNegativeWindows(windows) {
  const sorted = [...windows].sort(
    (left, right) =>
      left.startTimestampNs - right.startTimestampNs ||
      left.endTimestampNs - right.endTimestampNs
  );
  const merged = [];

  for (const interval of sorted) {
    const previous = merged.at(-1);
    if (!previous || interval.startTimestampNs > previous.endTimestampNs) {
      merged.push({ ...interval });
    } else {
      previous.endTimestampNs = Math.max(previous.endTimestampNs, interval.endTimestampNs);
    }
  }

  return merged;
}

function pointIsInWindows(timestampNs, windows) {
  let low = 0;
  let high = windows.length - 1;
  while (low <= high) {
    const middle = Math.floor((low + high) / 2);
    const interval = windows[middle];
    if (timestampNs < interval.startTimestampNs) {
      high = middle - 1;
    } else if (timestampNs >= interval.endTimestampNs) {
      low = middle + 1;
    } else {
      return true;
    }
  }
  return false;
}

function lowerBound(values, target) {
  let low = 0;
  let high = values.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (values[middle] < target) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }
  return low;
}

function evaluateSession(session, policy) {
  const { selection, groundTruth, negativeWindows } = selectAnnotations(session.annotations);
  const mergedNegativeWindows = mergeNegativeWindows(negativeWindows);
  const matches = [];
  const falsePositives = [];
  const falseNegatives = [];

  for (const gesture of GESTURES) {
    const gestureResult = matchGestureEvents(
      groundTruth.filter((event) => event.gesture === gesture),
      session.predictions.filter((event) => event.gesture === gesture),
      policy
    );
    matches.push(...gestureResult.matches);
    falsePositives.push(...gestureResult.falsePositives);
    falseNegatives.push(...gestureResult.falseNegatives);
  }

  return {
    detector: session.detector,
    platformFamily: session.platformFamily ?? "undeclared",
    annotationSelection: selection,
    matches,
    falsePositives,
    falseNegatives,
    negativeWindows: mergedNegativeWindows
  };
}

function percentileNearestRank(values, percentile) {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.ceil(percentile * sorted.length) - 1];
}

function ratio(numerator, denominator) {
  return denominator === 0 ? null : numerator / denominator;
}

function safeAdd(left, right, label) {
  const result = left + right;
  if (!Number.isSafeInteger(result)) {
    throw new MotionEvaluationError("aggregateOverflow", `${label} exceeds safe integer range`);
  }
  return result;
}

function makeMetricBlock(facts, sessionCount) {
  const precision = ratio(facts.truePositives, facts.truePositives + facts.falsePositives);
  const recall = ratio(facts.truePositives, facts.truePositives + facts.falseNegatives);
  const f1Denominator =
    2 * facts.truePositives + facts.falsePositives + facts.falseNegatives;
  const f1 =
    f1Denominator === 0 ? null : (2 * facts.truePositives) / f1Denominator;

  return {
    events: {
      truePositives: facts.truePositives,
      falsePositives: facts.falsePositives,
      falseNegatives: facts.falseNegatives
    },
    rates: {
      precision,
      recall,
      f1,
      falsePositivesPerSession: facts.falsePositives / sessionCount,
      falsePositivesPerNegativeHour:
        facts.negativeExposureDurationNs === 0
          ? null
          : facts.falsePositivesInNegativeExposure /
            (facts.negativeExposureDurationNs / HOUR_NS)
    },
    latencyNs: {
      matchedEvents: facts.latenciesNs.length,
      p50: percentileNearestRank(facts.latenciesNs, 0.5),
      p95: percentileNearestRank(facts.latenciesNs, 0.95)
    },
    negativeExposure: {
      durationNs: facts.negativeExposureDurationNs,
      falsePositives: facts.falsePositivesInNegativeExposure
    }
  };
}

function emptyFacts() {
  return {
    truePositives: 0,
    falsePositives: 0,
    falseNegatives: 0,
    latenciesNs: [],
    negativeExposureDurationNs: 0,
    falsePositivesInNegativeExposure: 0
  };
}

function aggregateSessions(sessionResults) {
  const overallFacts = emptyFacts();
  const byGestureFacts = Object.fromEntries(GESTURES.map((gesture) => [gesture, emptyFacts()]));
  let trueNegatives = 0;
  let falsePositiveWindows = 0;

  for (const session of sessionResults) {
    const falsePositiveTimestamps = session.falsePositives
      .map((prediction) => prediction.timestampNs)
      .sort((left, right) => left - right);
    const exposureDurationNs = session.negativeWindows.reduce(
      (sum, interval) => sum + interval.endTimestampNs - interval.startTimestampNs,
      0
    );
    overallFacts.negativeExposureDurationNs = safeAdd(
      overallFacts.negativeExposureDurationNs,
      exposureDurationNs,
      "negative exposure duration"
    );
    for (const gesture of GESTURES) {
      byGestureFacts[gesture].negativeExposureDurationNs = safeAdd(
        byGestureFacts[gesture].negativeExposureDurationNs,
        exposureDurationNs,
        "negative exposure duration"
      );
    }

    for (const match of session.matches) {
      overallFacts.truePositives += 1;
      overallFacts.latenciesNs.push(match.latencyNs);
      const gestureFacts = byGestureFacts[match.groundTruth.gesture];
      gestureFacts.truePositives += 1;
      gestureFacts.latenciesNs.push(match.latencyNs);
    }

    for (const prediction of session.falsePositives) {
      overallFacts.falsePositives += 1;
      byGestureFacts[prediction.gesture].falsePositives += 1;
      if (pointIsInWindows(prediction.timestampNs, session.negativeWindows)) {
        overallFacts.falsePositivesInNegativeExposure += 1;
        byGestureFacts[prediction.gesture].falsePositivesInNegativeExposure += 1;
      }
    }

    for (const truth of session.falseNegatives) {
      overallFacts.falseNegatives += 1;
      byGestureFacts[truth.gesture].falseNegatives += 1;
    }

    for (const interval of session.negativeWindows) {
      const candidate = lowerBound(falsePositiveTimestamps, interval.startTimestampNs);
      const hasFalsePositive =
        candidate < falsePositiveTimestamps.length &&
        falsePositiveTimestamps[candidate] < interval.endTimestampNs;
      if (hasFalsePositive) {
        falsePositiveWindows += 1;
      } else {
        trueNegatives += 1;
      }
    }
  }

  const overall = makeMetricBlock(overallFacts, sessionResults.length);
  const negativeWindowCount = trueNegatives + falsePositiveWindows;
  if (negativeWindowCount > 0) {
    overall.negativeWindowClassification = {
      windows: negativeWindowCount,
      trueNegatives,
      falsePositiveWindows,
      specificity: trueNegatives / negativeWindowCount,
      accuracy:
        (overallFacts.truePositives + trueNegatives) /
        (overallFacts.truePositives + overallFacts.falseNegatives + negativeWindowCount)
    };
  }

  return {
    overall,
    byGesture: Object.fromEntries(
      GESTURES.map((gesture) => [
        gesture,
        makeMetricBlock(byGestureFacts[gesture], sessionResults.length)
      ])
    )
  };
}

function detectorKey(detector) {
  return `${detector.detectorId}\u0000${detector.detectorVersion}\u0000${detector.configurationIdentity}`;
}

function publicDetector(detector) {
  return {
    detectorId: detector.detectorId,
    detectorVersion: detector.detectorVersion,
    configurationIdentity: detector.configurationIdentity
  };
}

function groupKey(session, dimensions) {
  return dimensions
    .map((dimension) =>
      dimension === "detectorVersion" ? session.detector.detectorVersion : session.platformFamily
    )
    .join("\u0000");
}

function groupValues(session, dimensions) {
  return Object.fromEntries(
    dimensions.map((dimension) => [
      dimension,
      dimension === "detectorVersion" ? session.detector.detectorVersion : session.platformFamily
    ])
  );
}

function buildGroups(sessionResults, dimensions) {
  if (dimensions.length === 0) {
    return [];
  }
  const buckets = new Map();
  for (const session of sessionResults) {
    const key = groupKey(session, dimensions);
    const bucket = buckets.get(key) ?? {
      dimensions: groupValues(session, dimensions),
      sessions: []
    };
    bucket.sessions.push(session);
    buckets.set(key, bucket);
  }

  return [...buckets.values()]
    .sort((left, right) =>
      compareText(JSON.stringify(left.dimensions), JSON.stringify(right.dimensions))
    )
    .map((bucket) => ({
      dimensions: bucket.dimensions,
      sessions: bucket.sessions.length,
      ...aggregateSessions(bucket.sessions)
    }));
}

function gateChecks(overall, gates) {
  assertRate(gates.minPrecision, "minPrecision");
  assertRate(gates.minRecall, "minRecall");
  assertRate(gates.minF1, "minF1");
  assertNonNegative(gates.maxFalsePositivesPerSession, "maxFalsePositivesPerSession");
  assertNonNegative(
    gates.maxFalsePositivesPerNegativeHour,
    "maxFalsePositivesPerNegativeHour"
  );
  assertNonNegative(gates.maxP95LatencyMs, "maxP95LatencyMs");

  const definitions = [
    ["minPrecision", ">=", gates.minPrecision, overall.rates.precision],
    ["minRecall", ">=", gates.minRecall, overall.rates.recall],
    ["minF1", ">=", gates.minF1, overall.rates.f1],
    [
      "maxFalsePositivesPerSession",
      "<=",
      gates.maxFalsePositivesPerSession,
      overall.rates.falsePositivesPerSession
    ],
    [
      "maxFalsePositivesPerNegativeHour",
      "<=",
      gates.maxFalsePositivesPerNegativeHour,
      overall.rates.falsePositivesPerNegativeHour
    ],
    [
      "maxP95LatencyMs",
      "<=",
      gates.maxP95LatencyMs,
      overall.latencyNs.p95 === null ? null : overall.latencyNs.p95 / 1_000_000
    ]
  ];

  return definitions
    .filter(([, , threshold]) => threshold !== undefined)
    .map(([name, comparator, threshold, actual]) => {
      const passed =
        actual !== null && (comparator === ">=" ? actual >= threshold : actual <= threshold);
      return {
        name,
        comparator,
        threshold,
        actual,
        passed,
        ...(actual === null ? { reason: "metricUnavailable" } : {})
      };
    });
}

export function evaluateSessions(sessions, options = {}) {
  if (!Array.isArray(sessions) || sessions.length === 0) {
    throw new MotionEvaluationError("noSessions", "At least one session is required");
  }

  const policy = {
    earlyToleranceNs:
      options.earlyToleranceNs ?? DEFAULT_MATCHING_POLICY.earlyToleranceNs,
    lateToleranceNs: options.lateToleranceNs ?? DEFAULT_MATCHING_POLICY.lateToleranceNs
  };
  assertSafeNonNegativeInteger(policy.earlyToleranceNs, "earlyToleranceNs");
  assertSafeNonNegativeInteger(policy.lateToleranceNs, "lateToleranceNs");

  const groupBy = [...new Set(options.groupBy ?? [])];
  for (const dimension of groupBy) {
    if (!GROUP_DIMENSIONS.includes(dimension)) {
      throw new MotionEvaluationError(
        "invalidGroupDimension",
        `Unsupported group dimension ${dimension}`
      );
    }
  }

  const sessionResults = sessions.map((session) => evaluateSession(session, policy));
  const annotationSelection = emptyAnnotationSelection();
  for (const session of sessionResults) {
    addAnnotationSelection(annotationSelection, session.annotationSelection);
  }

  const detectors = new Map();
  for (const session of sessionResults) {
    detectors.set(detectorKey(session.detector), publicDetector(session.detector));
  }
  const aggregate = aggregateSessions(sessionResults);
  const checks = gateChecks(aggregate.overall, options.gates ?? {});

  return {
    reportVersion: REPORT_VERSION,
    evaluator: {
      name: "motion-eval",
      version: MOTION_EVAL_VERSION
    },
    matchingPolicy: {
      groundTruthAnnotationKind: "gestureCommit",
      groundTruthProvenance: "reviewedGroundTruth",
      latencyAnchor: "gestureCommit.timestampNs",
      algorithm: "earliestDeadlineMaximumCardinality",
      earlyToleranceNs: policy.earlyToleranceNs,
      lateToleranceNs: policy.lateToleranceNs
    },
    dataset: {
      sessions: sessionResults.length,
      annotationSelection
    },
    detectors: [...detectors.values()].sort((left, right) =>
      compareText(detectorKey(left), detectorKey(right))
    ),
    ...aggregate,
    groups: buildGroups(sessionResults, groupBy),
    gates: {
      configured: checks.length > 0,
      passed: checks.every((check) => check.passed),
      checks
    }
  };
}

export function sessionFromValidatedTrace(validationResult, detectorStreamId) {
  if (validationResult.traceState !== "finalizedComplete") {
    throw new MotionEvaluationError(
      "incompleteTrace",
      "Only finalized-complete traces are eligible for evaluation"
    );
  }

  const header = validationResult.records.find((record) => record.recordType === "traceHeader");
  if (!header) {
    throw new MotionEvaluationError(
      "missingCollectedHeader",
      "Trace validation must collect traceHeader records"
    );
  }
  const detectors = header.detectors ?? [];
  let detector;
  if (detectorStreamId !== undefined) {
    detector = detectors.find((candidate) => candidate.detectorStreamId === detectorStreamId);
    if (!detector) {
      throw new MotionEvaluationError(
        "detectorNotFound",
        `Requested detector stream is not declared in input`
      );
    }
  } else if (detectors.length === 1) {
    [detector] = detectors;
  } else {
    throw new MotionEvaluationError(
      "ambiguousDetector",
      detectors.length === 0
        ? "Input declares no detector stream"
        : "Input declares multiple detector streams; select one explicitly"
    );
  }

  return {
    detector,
    platformFamily: header.device?.platformFamily ?? "undeclared",
    annotations: validationResult.records.filter((record) => record.recordType === "annotation"),
    predictions: validationResult.records.filter(
      (record) =>
        record.recordType === "predictedEvent" &&
        record.detectorStreamId === detector.detectorStreamId
    )
  };
}
