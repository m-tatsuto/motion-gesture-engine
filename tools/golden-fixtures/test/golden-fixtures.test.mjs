import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  TraceValidationError,
  loadValidators,
  validateTrace
} from "../../validate-trace.mjs";
import {
  evaluateSessions,
  sessionFromValidatedTrace
} from "../../motion-eval/evaluator.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
const GOLDEN = path.join(ROOT, "fixtures", "golden");
const MANIFEST_SCHEMA_ID =
  "https://raw.githubusercontent.com/m-tatsuto/motion-gesture-engine/main/spec/v1/schema/golden-fixture-manifest.schema.json";
const REPORT_SCHEMA_ID =
  "https://raw.githubusercontent.com/m-tatsuto/motion-gesture-engine/main/spec/v1/schema/motion-evaluation-report.schema.json";
const REQUIRED_SCENARIOS = new Set([
  "neutral",
  "tiltForward",
  "tiltBackward",
  "thresholdEquality",
  "rearmBoundary",
  "repeatedGestures",
  "repeatedSamples",
  "equalTimestampSamples",
  "nearThresholdJitter",
  "shakeLikeNoise",
  "irregularSampling",
  "longTimingGap",
  "duplicatePrediction",
  "wrongDirection",
  "earlyBoundary",
  "lateBoundary"
]);
const FORBIDDEN_KEYS = new Set([
  "accountId",
  "deviceId",
  "exactModel",
  "location",
  "applicationLogs",
  "arbitraryLog"
]);

const { ajv, validators } = await loadValidators();
const manifest = JSON.parse(await readFile(path.join(GOLDEN, "manifest.json"), "utf8"));

function readJsonLines(contents) {
  return contents.trimEnd().split("\n").filter(Boolean).map((line) => JSON.parse(line));
}

async function readRecords(relativePath) {
  return readJsonLines(await readFile(path.join(GOLDEN, relativePath), "utf8"));
}

async function validate(relativePath, collectRecordTypes = []) {
  return validateTrace(
    {
      filePath: path.join(GOLDEN, relativePath),
      label: relativePath,
      collectRecordTypes
    },
    validators
  );
}

function assertNoForbiddenKeys(value, location = "fixture") {
  if (Array.isArray(value)) {
    value.forEach((entry, index) => assertNoForbiddenKeys(entry, `${location}[${index}]`));
    return;
  }
  if (!value || typeof value !== "object") return;
  for (const [key, child] of Object.entries(value)) {
    assert.equal(FORBIDDEN_KEYS.has(key), false, `${location} contains forbidden key ${key}`);
    assertNoForbiddenKeys(child, `${location}.${key}`);
  }
}

test("manifest is schema-valid, complete, synthetic, and publication-reviewed", () => {
  const validateManifest = ajv.getSchema(MANIFEST_SCHEMA_ID);
  assert.ok(validateManifest, "golden fixture manifest schema was not compiled");
  assert.equal(validateManifest(manifest), true, JSON.stringify(validateManifest.errors));

  const observedScenarios = new Set(
    [...manifest.baselineCases, ...manifest.evaluatorCases, ...manifest.replayErrorCases]
      .flatMap((entry) => entry.scenarioTags)
  );
  assert.deepEqual(
    [...REQUIRED_SCENARIOS].filter((scenario) => !observedScenarios.has(scenario)),
    []
  );
  for (const fixture of [...manifest.baselineCases, ...manifest.evaluatorCases]) {
    assert.equal(fixture.privacyTier, "synthetic");
    assert.equal(fixture.provenance.kind, "synthetic");
    assert.equal(fixture.publicationStatus, "publicApproved");
    assert.equal(fixture.reviewStatus, "reviewedSynthetic");
  }
  for (const forbiddenKey of FORBIDDEN_KEYS) {
    assert.ok(manifest.dataPolicy.forbiddenFields.includes(forbiddenKey));
  }
});

test("baseline traces, committed predictions, full traces, and reports agree", async () => {
  const validateReport = ajv.getSchema(REPORT_SCHEMA_ID);
  assert.ok(validateReport, "evaluation report schema was not compiled");

  for (const fixture of manifest.baselineCases) {
    const sourceRecords = await readRecords(fixture.tracePath);
    const expectedPredictions = await readRecords(fixture.expectedPredictionPath);
    const fullRecords = await readRecords(fixture.replayTraceWithPredictionsPath);
    assertNoForbiddenKeys(sourceRecords, fixture.id);
    assert.equal(sourceRecords[0].privacy.tier, "synthetic");
    assert.deepEqual(sourceRecords[0].detectors, [fixture.detector]);

    await validate(fixture.tracePath);
    const fullValidation = await validate(fixture.replayTraceWithPredictionsPath, [
      "traceHeader",
      "annotation",
      "predictedEvent"
    ]);
    assert.deepEqual(
      fullRecords.filter((record) => record.recordType === "predictedEvent"),
      expectedPredictions,
      `${fixture.id} committed full trace must contain exactly the prediction golden`
    );
    assert.deepEqual(
      fullRecords.filter((record) => !["predictedEvent", "traceFooter"].includes(record.recordType)),
      sourceRecords.filter((record) => !["predictedEvent", "traceFooter"].includes(record.recordType)),
      `${fixture.id} prediction injection must not modify source records`
    );

    const actualReport = evaluateSessions([sessionFromValidatedTrace(fullValidation)]);
    const expectedReport = JSON.parse(
      await readFile(path.join(GOLDEN, fixture.expectedEvaluationReportPath), "utf8")
    );
    assert.equal(validateReport(expectedReport), true, JSON.stringify(validateReport.errors));
    assert.deepEqual(actualReport, expectedReport, `${fixture.id} metric regression`);

    const hasNegativeWindow = fullRecords.some(
      (record) =>
        record.recordType === "annotation" &&
        record.annotationKind === "negativeWindow" &&
        record.provenance.kind === "reviewedGroundTruth"
    );
    assert.equal(
      "negativeWindowClassification" in expectedReport.overall,
      hasNegativeWindow,
      `${fixture.id} TN/specificity/accuracy availability`
    );
  }
});

test("evaluator regression fixes duplicate, direction, tolerance, exposure, and latency metrics", async () => {
  const validateReport = ajv.getSchema(REPORT_SCHEMA_ID);
  for (const fixture of manifest.evaluatorCases) {
    const records = await readRecords(fixture.tracePath);
    assertNoForbiddenKeys(records, fixture.id);
    const validation = await validate(fixture.tracePath, [
      "traceHeader",
      "annotation",
      "predictedEvent"
    ]);
    const actual = evaluateSessions([sessionFromValidatedTrace(validation)]);
    const expected = JSON.parse(
      await readFile(path.join(GOLDEN, fixture.expectedEvaluationReportPath), "utf8")
    );
    assert.equal(validateReport(expected), true, JSON.stringify(validateReport.errors));
    assert.deepEqual(actual, expected);
    assert.deepEqual(expected.overall.events, {
      truePositives: 2,
      falsePositives: 4,
      falseNegatives: 2
    });
    assert.deepEqual(expected.overall.latencyNs, {
      matchedEvents: 2,
      p50: -100_000_000,
      p95: 500_000_000
    });
    assert.equal(expected.overall.rates.falsePositivesPerNegativeHour, 2);
  }
});

test("manifested malformed and incomplete traces retain stable validation failures", async () => {
  for (const fixture of manifest.replayErrorCases) {
    await assert.rejects(
      validate(fixture.tracePath),
      (error) =>
        error instanceof TraceValidationError && error.code === fixture.expectedSchemaErrorCode,
      fixture.id
    );
  }
});
