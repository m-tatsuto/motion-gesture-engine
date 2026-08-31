#!/usr/bin/env node

import Ajv2020 from "ajv/dist/2020.js";
import { createReadStream } from "node:fs";
import { readFile, readdir } from "node:fs/promises";
import { createInterface } from "node:readline";
import { Readable, Transform } from "node:stream";
import { fileURLToPath } from "node:url";
import { TextDecoder } from "node:util";
import { createGunzip, gzipSync } from "node:zlib";
import path from "node:path";

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SCHEMA_DIR = path.join(PROJECT_ROOT, "spec", "v1", "schema");
const FIXTURE_DIR = path.join(PROJECT_ROOT, "fixtures", "v1");
const SUPPORTED_SCHEMA_VERSION = "1.0.0-draft.1";
const SUPPORTED_CORE_SPEC_VERSION = "1.0.0-draft.1";
const MAX_LINE_BYTES = 1024 * 1024;
const MAX_DECOMPRESSED_BYTES = 256 * 1024 * 1024;
const MAX_RECORDS = 5_000_000;

const SCHEMA_FILES = [
  "common.schema.json",
  "motion-sample.schema.json",
  "annotation.schema.json",
  "predicted-event.schema.json",
  "display-rotation-change.schema.json",
  "capability-change.schema.json",
  "motion-trace-header.schema.json",
  "motion-trace-footer.schema.json",
  "motion-trace-record.schema.json",
  "motion-trace.schema.json"
];

const SCHEMA_ID_BASE =
  "https://raw.githubusercontent.com/m-tatsuto/motion-gesture-engine/main/spec/v1/schema/";

const SCHEMA_BY_RECORD_TYPE = new Map([
  ["traceHeader", `${SCHEMA_ID_BASE}motion-trace-header.schema.json`],
  ["sample", `${SCHEMA_ID_BASE}motion-sample.schema.json`],
  ["annotation", `${SCHEMA_ID_BASE}annotation.schema.json`],
  ["predictedEvent", `${SCHEMA_ID_BASE}predicted-event.schema.json`],
  ["displayRotationChange", `${SCHEMA_ID_BASE}display-rotation-change.schema.json`],
  ["capabilityChange", `${SCHEMA_ID_BASE}capability-change.schema.json`],
  ["traceFooter", `${SCHEMA_ID_BASE}motion-trace-footer.schema.json`]
]);

const COUNT_KEY_BY_RECORD_TYPE = new Map([
  ["sample", "samples"],
  ["annotation", "annotations"],
  ["predictedEvent", "predictedEvents"],
  ["displayRotationChange", "displayRotationChanges"],
  ["capabilityChange", "capabilityChanges"]
]);

const SIGNAL_KIND_BY_FIELD = new Map([
  ["gravity", "gravity"],
  ["userAcceleration", "userAcceleration"],
  ["rotationRate", "rotationRate"],
  ["attitude", "attitude"]
]);

class TraceValidationError extends Error {
  constructor(code, message, options = {}) {
    super(message);
    this.name = "TraceValidationError";
    this.code = code;
    this.line = options.line;
    this.traceState = options.traceState;
    this.exitCode = options.exitCode ?? 1;
  }
}

class JsonLinesContractTransform extends Transform {
  constructor() {
    super();
    this.firstBytes = [];
    this.lastByte = undefined;
    this.lineBytes = 0;
    this.totalBytes = 0;
    this.utf8Decoder = new TextDecoder("utf-8", { fatal: true });
  }

  _transform(chunk, _encoding, callback) {
    try {
      try {
        this.utf8Decoder.decode(chunk, { stream: true });
      } catch (error) {
        throw new TraceValidationError("invalidUtf8", error.message);
      }
      this.totalBytes += chunk.length;
      if (this.totalBytes > MAX_DECOMPRESSED_BYTES) {
        throw new TraceValidationError(
          "traceTooLarge",
          `Decompressed trace exceeds ${MAX_DECOMPRESSED_BYTES} bytes`
        );
      }
      for (const byte of chunk) {
        if (this.firstBytes.length < 3) {
          this.firstBytes.push(byte);
          if (
            this.firstBytes.length === 3 &&
            this.firstBytes[0] === 0xef &&
            this.firstBytes[1] === 0xbb &&
            this.firstBytes[2] === 0xbf
          ) {
            throw new TraceValidationError("byteOrderMarkForbidden", "UTF-8 BOM is forbidden");
          }
        }

        if (byte === 0x0d) {
          throw new TraceValidationError("carriageReturnForbidden", "JSON Lines must use LF, not CRLF");
        }

        if (byte === 0x0a) {
          this.lineBytes = 0;
        } else {
          this.lineBytes += 1;
          if (this.lineBytes > MAX_LINE_BYTES) {
            throw new TraceValidationError(
              "lineTooLong",
              `JSON line exceeds ${MAX_LINE_BYTES} bytes`
            );
          }
        }
        this.lastByte = byte;
      }
      this.push(chunk);
      callback();
    } catch (error) {
      callback(error);
    }
  }

  _flush(callback) {
    try {
      this.utf8Decoder.decode();
    } catch (error) {
      callback(new TraceValidationError("invalidUtf8", error.message));
      return;
    }
    if (this.lastByte !== 0x0a) {
      callback(new TraceValidationError("finalLfMissing", "A final LF is required"));
      return;
    }
    callback();
  }
}

async function loadValidators() {
  const ajv = new Ajv2020({
    allErrors: true,
    strict: true,
    strictNumbers: true,
    validateFormats: false
  });

  const loaded = [];
  for (const file of SCHEMA_FILES) {
    const schemaPath = path.join(SCHEMA_DIR, file);
    const schema = JSON.parse(await readFile(schemaPath, "utf8"));
    ajv.addSchema(schema);
    loaded.push({ file, id: schema.$id });
  }

  const validators = new Map();
  for (const [recordType, schemaId] of SCHEMA_BY_RECORD_TYPE) {
    const validator = ajv.getSchema(schemaId);
    if (!validator) {
      throw new Error(`Schema did not compile: ${schemaId}`);
    }
    validators.set(recordType, validator);
  }

  for (const { id } of loaded) {
    if (!ajv.getSchema(id)) {
      throw new Error(`Schema did not compile: ${id}`);
    }
  }

  return { ajv, validators, loaded };
}

function formatAjvErrors(errors) {
  return (errors ?? [])
    .slice(0, 8)
    .map((error) => `${error.instancePath || "/"} ${error.message}`)
    .join("; ");
}

function pairIsAfter(current, previous, sequenceKey) {
  return (
    current.timestampNs > previous.timestampNs ||
    (current.timestampNs === previous.timestampNs && current[sequenceKey] > previous[sequenceKey])
  );
}

function validateOrthonormalMatrix(matrix, tolerance) {
  const rows = [matrix.slice(0, 3), matrix.slice(3, 6), matrix.slice(6, 9)];
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  const cross = (a, b) => [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0]
  ];

  for (let index = 0; index < 3; index += 1) {
    if (Math.abs(dot(rows[index], rows[index]) - 1) > tolerance) {
      return false;
    }
  }
  if (
    Math.abs(dot(rows[0], rows[1])) > tolerance ||
    Math.abs(dot(rows[0], rows[2])) > tolerance ||
    Math.abs(dot(rows[1], rows[2])) > tolerance
  ) {
    return false;
  }
  const determinant = dot(rows[0], cross(rows[1], rows[2]));
  return Math.abs(determinant - 1) <= tolerance;
}

function validateTermination(footer, line) {
  const allowed = {
    complete: new Set(["requestedStop"]),
    bounded: new Set(["durationLimit", "sampleLimit", "byteLimit"]),
    cancelled: new Set(["callerCancelled"]),
    failed: new Set(["sourceFailure", "ioFailure"])
  };
  if (!allowed[footer.finalizationStatus].has(footer.terminationReason)) {
    throw new TraceValidationError(
      "invalidTermination",
      `${footer.finalizationStatus} is incompatible with ${footer.terminationReason}`,
      { line }
    );
  }
  if (footer.finalizationStatus === "failed" && footer.failureCode === undefined) {
    throw new TraceValidationError("invalidTermination", "failed footer requires failureCode", {
      line
    });
  }
  if (footer.finalizationStatus !== "failed" && footer.failureCode !== undefined) {
    throw new TraceValidationError("invalidTermination", "failureCode is allowed only for failed", {
      line
    });
  }
}

function sourceFromOptions(options) {
  if (options.buffer) {
    return Readable.from([options.buffer]);
  }
  return createReadStream(options.filePath);
}

function inputLabel(options) {
  return options.label ?? options.filePath ?? "<buffer>";
}

async function validateTrace(options, validators) {
  const label = inputLabel(options);
  const raw = sourceFromOptions(options);
  const compressed = options.gzip ?? label.endsWith(".gz");
  const contracted = new JsonLinesContractTransform();
  if (compressed) {
    const gunzip = createGunzip();
    raw.on("error", (error) => gunzip.destroy(error));
    gunzip.on("error", (error) => contracted.destroy(error));
    raw.pipe(gunzip).pipe(contracted);
  } else {
    raw.on("error", (error) => contracted.destroy(error));
    raw.pipe(contracted);
  }
  const lines = createInterface({ input: contracted, crlfDelay: Number.POSITIVE_INFINITY });

  const counts = {
    samples: 0,
    annotations: 0,
    predictedEvents: 0,
    displayRotationChanges: 0,
    capabilityChanges: 0
  };
  const sampleSequences = new Set();
  const annotationIds = new Set();
  const eventIds = new Set();
  const changeSequences = new Set();
  const detectorEventSequences = new Map();
  const lastPredictionByDetector = new Map();
  const timingByCapability = new Map();
  const reviewedSourceAnnotationIds = [];
  const predictedSourceSampleSequences = [];
  const observedPrivacyClasses = new Set(["motionSensorData"]);

  let header;
  let footer;
  let lineNumber = 0;
  let lastSample;
  let maxTimestampNs = 0;
  let capabilities = new Map();
  let capabilityAvailability = new Map();
  let detectors = new Set();

  try {
    for await (const line of lines) {
      lineNumber += 1;
      if (lineNumber > MAX_RECORDS + 2) {
        throw new TraceValidationError(
          "recordLimitExceeded",
          `Trace exceeds ${MAX_RECORDS} body records`,
          { line: lineNumber }
        );
      }
      if (line.length === 0) {
        throw new TraceValidationError("blankLine", "Blank JSON Lines are forbidden", {
          line: lineNumber
        });
      }
      if (footer) {
        throw new TraceValidationError("recordAfterFooter", "No record may follow traceFooter", {
          line: lineNumber
        });
      }

      let record;
      try {
        record = JSON.parse(line);
      } catch (error) {
        throw new TraceValidationError("invalidJson", error.message, { line: lineNumber });
      }

      if (!record || typeof record !== "object" || Array.isArray(record)) {
        throw new TraceValidationError("schemaViolation", "Each line must be a JSON object", {
          line: lineNumber
        });
      }

      if (lineNumber === 1 && record.recordType !== "traceHeader") {
        throw new TraceValidationError("headerNotFirst", "Line 1 must be traceHeader", {
          line: lineNumber
        });
      }
      if (record.recordType === "traceHeader" && lineNumber !== 1) {
        throw new TraceValidationError("duplicateHeader", "traceHeader is allowed only on line 1", {
          line: lineNumber
        });
      }
      if (
        (record.recordType === "traceHeader" || record.recordType === "traceFooter") &&
        record.schemaVersion !== SUPPORTED_SCHEMA_VERSION
      ) {
        throw new TraceValidationError(
          "unsupportedSchemaVersion",
          `Unsupported schemaVersion ${String(record.schemaVersion)}`,
          { line: lineNumber }
        );
      }
      if (
        record.recordType === "traceHeader" &&
        record.coreSpecVersion !== SUPPORTED_CORE_SPEC_VERSION
      ) {
        throw new TraceValidationError(
          "unsupportedSpecVersion",
          `Unsupported coreSpecVersion ${String(record.coreSpecVersion)}`,
          { line: lineNumber }
        );
      }

      const validator = validators.get(record.recordType);
      if (!validator) {
        throw new TraceValidationError(
          "unknownRecordType",
          `Unknown recordType ${String(record.recordType)}`,
          { line: lineNumber }
        );
      }
      if (!validator(record)) {
        throw new TraceValidationError("schemaViolation", formatAjvErrors(validator.errors), {
          line: lineNumber
        });
      }

      if (record.recordType === "traceHeader") {
        header = record;
        capabilities = new Map();
        capabilityAvailability = new Map();
        for (const capability of header.capabilities) {
          if (capabilities.has(capability.capabilityId)) {
            throw new TraceValidationError(
              "duplicateCapability",
              `Duplicate capabilityId ${capability.capabilityId}`,
              { line: lineNumber }
            );
          }
          if (capability.conversions.includes("none") && capability.conversions.length !== 1) {
            throw new TraceValidationError(
              "invalidConversion",
              `Capability ${capability.capabilityId} combines none with conversions`,
              { line: lineNumber }
            );
          }
          if (capability.requirement === "required" && capability.availability !== "available") {
            throw new TraceValidationError(
              "invalidCapabilitySnapshot",
              `Required capability ${capability.capabilityId} is not available`,
              { line: lineNumber }
            );
          }
          if (
            capability.signalKind === "rotationRate" &&
            capability.biasCorrection === "notApplicable"
          ) {
            throw new TraceValidationError(
              "invalidCapabilitySnapshot",
              `Rotation capability ${capability.capabilityId} must declare raw or biasCorrected`,
              { line: lineNumber }
            );
          }
          if (
            capability.signalKind !== "rotationRate" &&
            capability.biasCorrection !== "notApplicable"
          ) {
            throw new TraceValidationError(
              "invalidCapabilitySnapshot",
              `Non-rotation capability ${capability.capabilityId} cannot declare bias correction`,
              { line: lineNumber }
            );
          }
          capabilities.set(capability.capabilityId, capability);
          capabilityAvailability.set(capability.capabilityId, capability.availability);
          timingByCapability.set(capability.capabilityId, {
            count: 0,
            lastTimestampNs: undefined,
            minimumIntervalNs: undefined,
            maximumIntervalNs: undefined
          });
        }

        detectors = new Set();
        for (const detector of header.detectors ?? []) {
          if (detectors.has(detector.detectorStreamId)) {
            throw new TraceValidationError(
              "duplicateDetector",
              `Duplicate detectorStreamId ${detector.detectorStreamId}`,
              { line: lineNumber }
            );
          }
          detectors.add(detector.detectorStreamId);
          detectorEventSequences.set(detector.detectorStreamId, new Set());
        }

        if (
          !validateOrthonormalMatrix(
            header.session.gestureFrameFromDeviceRowMajor,
            header.conventions.frameOrthonormalTolerance
          )
        ) {
          throw new TraceValidationError(
            "invalidGestureFrame",
            "gestureFrameFromDeviceRowMajor must be right-handed and orthonormal",
            { line: lineNumber }
          );
        }
        continue;
      }

      const countKey = COUNT_KEY_BY_RECORD_TYPE.get(record.recordType);
      if (countKey) {
        counts[countKey] += 1;
      }

      if (record.timestampNs !== undefined) {
        maxTimestampNs = Math.max(maxTimestampNs, record.timestampNs);
      }

      if (record.recordType === "sample") {
        if (sampleSequences.has(record.sequence)) {
          throw new TraceValidationError(
            "duplicateSampleSequence",
            `Duplicate sample sequence ${record.sequence}`,
            { line: lineNumber }
          );
        }
        sampleSequences.add(record.sequence);
        if (lastSample && !pairIsAfter(record, lastSample, "sequence")) {
          throw new TraceValidationError(
            "nonMonotonicSample",
            "Samples must be ordered by (timestampNs, sequence)",
            { line: lineNumber }
          );
        }
        lastSample = record;

        for (const [field, observation] of Object.entries(record.signals)) {
          const capability = capabilities.get(observation.capabilityId);
          if (!capability) {
            throw new TraceValidationError(
              "unknownCapability",
              `Unknown capabilityId ${observation.capabilityId}`,
              { line: lineNumber }
            );
          }
          const expectedSignalKind = SIGNAL_KIND_BY_FIELD.get(field);
          if (capability.signalKind !== expectedSignalKind) {
            throw new TraceValidationError(
              "capabilitySignalMismatch",
              `${observation.capabilityId} is ${capability.signalKind}, not ${expectedSignalKind}`,
              { line: lineNumber }
            );
          }
          if (capabilityAvailability.get(observation.capabilityId) !== "available") {
            throw new TraceValidationError(
              "capabilityUnavailable",
              `${observation.capabilityId} is not available at this record`,
              { line: lineNumber }
            );
          }

          if (field === "attitude") {
            if (!header.session.attitudeReference) {
              throw new TraceValidationError(
                "attitudeReferenceMissing",
                "Attitude observation requires session.attitudeReference",
                { line: lineNumber }
              );
            }
            const norm = Math.hypot(...observation.value);
            if (Math.abs(norm - 1) > header.conventions.attitudeQuaternionNormTolerance) {
              throw new TraceValidationError(
                "invalidQuaternionNorm",
                `Attitude quaternion norm ${norm} is outside tolerance`,
                { line: lineNumber }
              );
            }
          }

          const timing = timingByCapability.get(observation.capabilityId);
          if (timing.lastTimestampNs !== undefined) {
            const interval = record.timestampNs - timing.lastTimestampNs;
            timing.minimumIntervalNs =
              timing.minimumIntervalNs === undefined
                ? interval
                : Math.min(timing.minimumIntervalNs, interval);
            timing.maximumIntervalNs =
              timing.maximumIntervalNs === undefined
                ? interval
                : Math.max(timing.maximumIntervalNs, interval);
          }
          timing.lastTimestampNs = record.timestampNs;
          timing.count += 1;
        }
        continue;
      }

      if (record.recordType === "annotation") {
        if (annotationIds.has(record.annotationId)) {
          throw new TraceValidationError(
            "duplicateAnnotationId",
            `Duplicate annotationId ${record.annotationId}`,
            { line: lineNumber }
          );
        }
        annotationIds.add(record.annotationId);
        observedPrivacyClasses.add("gestureAnnotation");
        if (record.endTimestampNs !== undefined && record.endTimestampNs < record.timestampNs) {
          throw new TraceValidationError(
            "invalidAnnotationInterval",
            "endTimestampNs precedes timestampNs",
            { line: lineNumber }
          );
        }
        if (record.endTimestampNs !== undefined) {
          maxTimestampNs = Math.max(maxTimestampNs, record.endTimestampNs);
        }
        if (record.provenance.kind === "userReport") {
          observedPrivacyClasses.add("userReport");
        }
        if (record.provenance.kind === "reviewedGroundTruth") {
          for (const sourceId of record.provenance.sourceAnnotationIds) {
            reviewedSourceAnnotationIds.push({ sourceId, line: lineNumber });
          }
        }
        continue;
      }

      if (record.recordType === "predictedEvent") {
        if (!detectors.has(record.detectorStreamId)) {
          throw new TraceValidationError(
            "unknownDetector",
            `Unknown detectorStreamId ${record.detectorStreamId}`,
            { line: lineNumber }
          );
        }
        if (eventIds.has(record.eventId)) {
          throw new TraceValidationError("duplicateEventId", `Duplicate eventId ${record.eventId}`, {
            line: lineNumber
          });
        }
        eventIds.add(record.eventId);

        const sequences = detectorEventSequences.get(record.detectorStreamId);
        if (sequences.has(record.eventSequence)) {
          throw new TraceValidationError(
            "duplicateEventSequence",
            `Duplicate eventSequence ${record.eventSequence} for ${record.detectorStreamId}`,
            { line: lineNumber }
          );
        }
        sequences.add(record.eventSequence);

        const previous = lastPredictionByDetector.get(record.detectorStreamId);
        if (previous && !pairIsAfter(record, previous, "eventSequence")) {
          throw new TraceValidationError(
            "nonMonotonicPrediction",
            `Predictions for ${record.detectorStreamId} are not ordered`,
            { line: lineNumber }
          );
        }
        lastPredictionByDetector.set(record.detectorStreamId, record);
        if (record.sourceSampleSequence !== undefined) {
          predictedSourceSampleSequences.push({
            sequence: record.sourceSampleSequence,
            line: lineNumber
          });
        }
        continue;
      }

      if (
        record.recordType === "displayRotationChange" ||
        record.recordType === "capabilityChange"
      ) {
        const key = `${record.recordType}:${record.changeSequence}`;
        if (changeSequences.has(key)) {
          throw new TraceValidationError(
            "duplicateChangeSequence",
            `Duplicate ${key}`,
            { line: lineNumber }
          );
        }
        changeSequences.add(key);
        if (
          record.recordType === "capabilityChange" &&
          !capabilities.has(record.capabilityId)
        ) {
          throw new TraceValidationError(
            "unknownCapability",
            `Unknown capabilityId ${record.capabilityId}`,
            { line: lineNumber }
          );
        }
        if (record.recordType === "capabilityChange" && record.availability !== undefined) {
          capabilityAvailability.set(record.capabilityId, record.availability);
        }
        continue;
      }

      if (record.recordType === "traceFooter") {
        footer = record;
      }
    }
  } catch (error) {
    if (error instanceof TraceValidationError) {
      throw error;
    }
    throw new TraceValidationError("ioFailure", error.message);
  }

  if (!header) {
    throw new TraceValidationError("headerNotFirst", "Trace header is missing", {
      traceState: "unfinalized"
    });
  }
  if (!footer) {
    throw new TraceValidationError("incompleteTrace", "Valid terminal traceFooter is missing", {
      traceState: "unfinalized",
      exitCode: 2
    });
  }

  validateTermination(footer, lineNumber);

  if (
    header.orderingPolicy.sampleReordering.kind === "none" &&
    footer.reorderedSamples !== 0
  ) {
    throw new TraceValidationError(
      "reorderDiagnosticsMismatch",
      "reorderedSamples must be zero when sampleReordering is none",
      { line: lineNumber }
    );
  }

  for (const [key, actual] of Object.entries(counts)) {
    if (footer.recordCounts[key] !== actual) {
      throw new TraceValidationError(
        "footerCountMismatch",
        `${key}: footer=${footer.recordCounts[key]}, actual=${actual}`,
        { line: lineNumber }
      );
    }
  }

  const droppedTotal = footer.droppedSamples.byReason.reduce(
    (sum, entry) => sum + entry.count,
    0
  );
  if (droppedTotal !== footer.droppedSamples.total) {
    throw new TraceValidationError(
      "droppedCountMismatch",
      `Dropped total ${footer.droppedSamples.total} does not equal ${droppedTotal}`,
      { line: lineNumber }
    );
  }

  const droppedKeys = new Set();
  for (const entry of footer.droppedSamples.byReason) {
    if (entry.capabilityId !== undefined && !capabilities.has(entry.capabilityId)) {
      throw new TraceValidationError(
        "unknownCapability",
        `Dropped count references ${entry.capabilityId}`,
        { line: lineNumber }
      );
    }
    const key = `${entry.reason}:${entry.capabilityId ?? "*"}`;
    if (droppedKeys.has(key)) {
      throw new TraceValidationError("duplicateDroppedReason", `Duplicate dropped reason ${key}`, {
        line: lineNumber
      });
    }
    droppedKeys.add(key);
  }

  const footerTiming = new Map();
  for (const timing of footer.observedTiming) {
    if (!capabilities.has(timing.capabilityId)) {
      throw new TraceValidationError(
        "unknownCapability",
        `Observed timing references ${timing.capabilityId}`,
        { line: lineNumber }
      );
    }
    if (footerTiming.has(timing.capabilityId)) {
      throw new TraceValidationError(
        "duplicateObservedTiming",
        `Duplicate observed timing for ${timing.capabilityId}`,
        { line: lineNumber }
      );
    }
    footerTiming.set(timing.capabilityId, timing);
  }

  for (const [capabilityId, actual] of timingByCapability) {
    const declared = footerTiming.get(capabilityId);
    if (!declared) {
      throw new TraceValidationError(
        "observedTimingMismatch",
        `Missing observed timing for ${capabilityId}`,
        { line: lineNumber }
      );
    }
    if (
      declared.acceptedObservationCount !== actual.count ||
      declared.minimumIntervalNs !== actual.minimumIntervalNs ||
      declared.maximumIntervalNs !== actual.maximumIntervalNs
    ) {
      throw new TraceValidationError(
        "observedTimingMismatch",
        `Observed timing does not match samples for ${capabilityId}`,
        { line: lineNumber }
      );
    }
  }
  if (footerTiming.size !== timingByCapability.size) {
    throw new TraceValidationError(
      "observedTimingMismatch",
      "Observed timing capability set does not match header",
      { line: lineNumber }
    );
  }

  if (footer.durationNs < maxTimestampNs) {
    throw new TraceValidationError(
      "durationMismatch",
      `durationNs ${footer.durationNs} precedes record time ${maxTimestampNs}`,
      { line: lineNumber }
    );
  }

  if (header.recorderLimits) {
    const limits = header.recorderLimits;
    if (
      footer.durationNs > limits.maximumDurationNs ||
      counts.samples > limits.maximumSamples ||
      contracted.totalBytes > limits.maximumBytes
    ) {
      throw new TraceValidationError(
        "recorderLimitMismatch",
        `Trace exceeds declared recorder limits: duration=${footer.durationNs}/${limits.maximumDurationNs}, samples=${counts.samples}/${limits.maximumSamples}, bytes=${contracted.totalBytes}/${limits.maximumBytes}`,
        { line: lineNumber }
      );
    }
  }

  for (const reference of reviewedSourceAnnotationIds) {
    if (!annotationIds.has(reference.sourceId)) {
      throw new TraceValidationError(
        "unknownAnnotationReference",
        `Reviewed annotation references ${reference.sourceId}`,
        { line: reference.line }
      );
    }
  }
  for (const reference of predictedSourceSampleSequences) {
    if (!sampleSequences.has(reference.sequence)) {
      throw new TraceValidationError(
        "unknownSampleReference",
        `Predicted event references sample sequence ${reference.sequence}`,
        { line: reference.line }
      );
    }
  }

  const declaredPrivacyClasses = new Set(header.privacy.dataClasses);
  for (const dataClass of observedPrivacyClasses) {
    if (!declaredPrivacyClasses.has(dataClass)) {
      throw new TraceValidationError(
        "privacyDeclarationMismatch",
        `Header does not declare ${dataClass}`,
        { line: 1 }
      );
    }
  }

  return {
    label,
    traceState:
      footer.finalizationStatus === "complete" || footer.finalizationStatus === "bounded"
        ? "finalizedComplete"
        : "finalizedIncomplete",
    recordCounts: counts,
    droppedSamples: footer.droppedSamples.total
  };
}

async function runFixtureSuite(validators) {
  const manifestPath = path.join(FIXTURE_DIR, "manifest.json");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  let passed = 0;

  for (const fixture of manifest.valid) {
    const filePath = path.join(FIXTURE_DIR, fixture.path);
    const result = await validateTrace({ filePath }, validators);
    if (result.traceState !== fixture.expectedTraceState) {
      throw new Error(
        `${fixture.path}: expected ${fixture.expectedTraceState}, got ${result.traceState}`
      );
    }
    console.log(`PASS valid ${fixture.path} (${result.traceState})`);
    passed += 1;
  }

  for (const fixture of manifest.invalid) {
    const filePath = path.join(FIXTURE_DIR, fixture.path);
    try {
      await validateTrace({ filePath }, validators);
      throw new Error(`${fixture.path}: expected ${fixture.expectedErrorCode}, validation passed`);
    } catch (error) {
      if (!(error instanceof TraceValidationError)) {
        throw error;
      }
      if (error.code !== fixture.expectedErrorCode) {
        throw new Error(
          `${fixture.path}: expected ${fixture.expectedErrorCode}, got ${error.code}: ${error.message}`
        );
      }
      console.log(`PASS invalid ${fixture.path} (${error.code})`);
      passed += 1;
    }
  }

  for (const fixture of manifest.gzipRoundTrips) {
    const sourcePath = path.join(FIXTURE_DIR, fixture.path);
    const source = await readFile(sourcePath);
    const compressed = gzipSync(source, { mtime: 0 });
    const result = await validateTrace(
      { buffer: compressed, gzip: true, label: `${fixture.path} (gzip)` },
      validators
    );
    if (result.traceState !== fixture.expectedTraceState) {
      throw new Error(
        `${fixture.path} gzip: expected ${fixture.expectedTraceState}, got ${result.traceState}`
      );
    }
    console.log(`PASS gzip ${fixture.path} (${result.traceState})`);
    passed += 1;
  }

  const forbiddenCatalogPath = path.join(FIXTURE_DIR, manifest.forbiddenFieldCases);
  const forbiddenCatalog = JSON.parse(await readFile(forbiddenCatalogPath, "utf8"));
  const baseText = await readFile(path.join(FIXTURE_DIR, forbiddenCatalog.basePath), "utf8");
  const baseLines = baseText.trimEnd().split("\n");
  for (const fixture of forbiddenCatalog.cases) {
    const mutatedLines = [...baseLines];
    const mutatedHeader = JSON.parse(mutatedLines[0]);
    mutatedHeader[fixture.field] = fixture.value;
    mutatedLines[0] = JSON.stringify(mutatedHeader);
    const mutated = Buffer.from(`${mutatedLines.join("\n")}\n`, "utf8");
    try {
      await validateTrace(
        { buffer: mutated, label: `forbidden field ${fixture.field}` },
        validators
      );
      throw new Error(`${fixture.field}: forbidden field validation passed`);
    } catch (error) {
      if (!(error instanceof TraceValidationError)) {
        throw error;
      }
      if (error.code !== forbiddenCatalog.expectedErrorCode) {
        throw new Error(
          `${fixture.field}: expected ${forbiddenCatalog.expectedErrorCode}, got ${error.code}`
        );
      }
      console.log(`PASS forbidden ${fixture.field} (${error.code})`);
      passed += 1;
    }
  }

  const containerCatalogPath = path.join(FIXTURE_DIR, manifest.containerCases);
  const containerCatalog = JSON.parse(await readFile(containerCatalogPath, "utf8"));
  const containerBase = await readFile(path.join(FIXTURE_DIR, containerCatalog.basePath));
  for (const fixture of containerCatalog.cases) {
    let mutated;
    let gzip = false;
    switch (fixture.mutation) {
      case "removeFinalLf":
        mutated = containerBase.subarray(0, containerBase.length - 1);
        break;
      case "insertCrLf":
        mutated = Buffer.from(containerBase.toString("utf8").replace("\n", "\r\n"), "utf8");
        break;
      case "prependBom":
        mutated = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), containerBase]);
        break;
      case "insertBlankLine":
        mutated = Buffer.from(containerBase.toString("utf8").replace("\n", "\n\n"), "utf8");
        break;
      case "corruptUtf8": {
        mutated = Buffer.from(containerBase);
        const marker = Buffer.from("motionGestureCore", "utf8");
        const markerIndex = mutated.indexOf(marker);
        if (markerIndex < 0) {
          throw new Error("UTF-8 fixture marker is missing");
        }
        mutated[markerIndex] = 0xff;
        break;
      }
      case "truncateGzip": {
        const compressed = gzipSync(containerBase, { mtime: 0 });
        mutated = compressed.subarray(0, compressed.length - 8);
        gzip = true;
        break;
      }
      default:
        throw new Error(`Unknown container mutation ${fixture.mutation}`);
    }

    try {
      await validateTrace(
        { buffer: mutated, gzip, label: `container ${fixture.mutation}` },
        validators
      );
      throw new Error(`${fixture.mutation}: invalid container validation passed`);
    } catch (error) {
      if (!(error instanceof TraceValidationError)) {
        throw error;
      }
      if (error.code !== fixture.expectedErrorCode) {
        throw new Error(
          `${fixture.mutation}: expected ${fixture.expectedErrorCode}, got ${error.code}`
        );
      }
      console.log(`PASS container ${fixture.mutation} (${error.code})`);
      passed += 1;
    }
  }

  console.log(`Fixture suite passed: ${passed} cases`);
}

function printUsage() {
  console.log(`Usage:
  node tools/validate-trace.mjs --schemas
  node tools/validate-trace.mjs --fixtures
  node tools/validate-trace.mjs <trace.mge.jsonl|trace.mge.jsonl.gz> [...]`);
}

async function main() {
  const args = process.argv.slice(2);
  const { validators, loaded } = await loadValidators();

  if (args.length === 1 && args[0] === "--schemas") {
    const files = (await readdir(SCHEMA_DIR)).filter((file) => file.endsWith(".schema.json"));
    if (files.length !== loaded.length) {
      throw new Error(`Loaded ${loaded.length} schemas but found ${files.length}`);
    }
    console.log(`Schemas compiled in strict mode: ${loaded.length}`);
    return;
  }

  if (args.length === 1 && args[0] === "--fixtures") {
    await runFixtureSuite(validators);
    return;
  }

  if (args.length === 0 || args.includes("--help")) {
    printUsage();
    process.exitCode = args.length === 0 ? 1 : 0;
    return;
  }

  let exitCode = 0;
  for (const input of args) {
    const filePath = path.resolve(process.cwd(), input);
    try {
      const result = await validateTrace({ filePath }, validators);
      console.log(`${input}: VALID ${result.traceState}`);
    } catch (error) {
      if (!(error instanceof TraceValidationError)) {
        throw error;
      }
      const location = error.line ? ` line=${error.line}` : "";
      const state = error.traceState ? ` state=${error.traceState}` : "";
      console.error(`${input}: ${error.code}${location}${state}: ${error.message}`);
      if (error.exitCode === 1) {
        exitCode = 1;
      } else if (exitCode === 0) {
        exitCode = error.exitCode;
      }
    }
  }
  process.exitCode = exitCode;
}

main().catch((error) => {
  console.error(error.stack ?? error.message);
  process.exitCode = 1;
});
