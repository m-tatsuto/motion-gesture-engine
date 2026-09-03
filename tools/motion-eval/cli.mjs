#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { mkdir, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  MOTION_EVAL_VERSION,
  MotionEvaluationError,
  evaluateSessions,
  sessionFromValidatedTrace
} from "./evaluator.mjs";
import { renderMarkdown } from "./report.mjs";
import {
  TraceValidationError,
  loadValidators,
  validateTrace
} from "../validate-trace.mjs";

const MODULE_PATH = fileURLToPath(import.meta.url);
const COLLECTED_RECORD_TYPES = ["traceHeader", "annotation", "predictedEvent"];

const VALUE_OPTIONS = new Map([
  ["--detector-stream", "detectorStreamId"],
  ["--early-tolerance-ms", "earlyToleranceMs"],
  ["--late-tolerance-ms", "lateToleranceMs"],
  ["--group-by", "groupBy"],
  ["--json", "jsonOutput"],
  ["--markdown", "markdownOutput"],
  ["--min-precision", "minPrecision"],
  ["--min-recall", "minRecall"],
  ["--min-f1", "minF1"],
  ["--max-fp-per-session", "maxFalsePositivesPerSession"],
  ["--max-fp-per-negative-hour", "maxFalsePositivesPerNegativeHour"],
  ["--max-p95-latency-ms", "maxP95LatencyMs"]
]);
const GATE_DESTINATIONS = new Set([
  "minPrecision",
  "minRecall",
  "minF1",
  "maxFalsePositivesPerSession",
  "maxFalsePositivesPerNegativeHour",
  "maxP95LatencyMs"
]);

function usage() {
  return `Usage:
  motion-eval [options] <trace.mge.jsonl|trace.mge.jsonl.gz> [...]

Output (Markdown is written to stdout when neither option is provided):
  --json <path|->                         Write the CI report as JSON
  --markdown <path|->                     Write the review report as Markdown

Matching:
  --detector-stream <id>                  Select a stream when a trace declares several
  --early-tolerance-ms <ms>               Default: 100
  --late-tolerance-ms <ms>                Default: 500
  --group-by <dimension[,dimension]>      detectorVersion or platformFamily

Regression gates (a failed gate exits 3 after reports are written):
  --min-precision <0..1>
  --min-recall <0..1>
  --min-f1 <0..1>
  --max-fp-per-session <number>
  --max-fp-per-negative-hour <number>
  --max-p95-latency-ms <ms>

Other:
  --help
  --version
`;
}

function takeOptionValue(args, index, option) {
  const value = args[index + 1];
  if (value === undefined) {
    throw new MotionEvaluationError("invalidArguments", `${option} requires a value`);
  }
  return value;
}

export function parseArguments(args) {
  const options = { inputs: [], groupBy: [], gates: {} };

  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--") {
      options.inputs.push(...args.slice(index + 1));
      break;
    }
    if (argument === "--help") {
      options.help = true;
      continue;
    }
    if (argument === "--version") {
      options.version = true;
      continue;
    }
    if (!argument.startsWith("--")) {
      options.inputs.push(argument);
      continue;
    }

    const destination = VALUE_OPTIONS.get(argument);
    if (!destination) {
      throw new MotionEvaluationError("invalidArguments", `Unknown option ${argument}`);
    }
    const value = takeOptionValue(args, index, argument);
    index += 1;
    if (destination === "groupBy") {
      options.groupBy.push(...value.split(",").filter((entry) => entry.length > 0));
    } else if (GATE_DESTINATIONS.has(destination)) {
      options.gates[destination] = value;
    } else {
      options[destination] = value;
    }
  }

  return options;
}

function numberOption(value, name) {
  if (value === undefined) {
    return undefined;
  }
  if (value.trim() === "" || !Number.isFinite(Number(value))) {
    throw new MotionEvaluationError("invalidArguments", `${name} must be a finite number`);
  }
  return Number(value);
}

function millisecondsToNanoseconds(value, name) {
  const milliseconds = numberOption(value, name);
  if (milliseconds === undefined) {
    return undefined;
  }
  const nanoseconds = Math.round(milliseconds * 1_000_000);
  if (milliseconds < 0 || !Number.isSafeInteger(nanoseconds)) {
    throw new MotionEvaluationError(
      "invalidArguments",
      `${name} must convert to a non-negative safe nanosecond value`
    );
  }
  return nanoseconds;
}

function normalizeOptions(parsed) {
  const gates = Object.fromEntries(
    Object.entries(parsed.gates).map(([name, value]) => [name, numberOption(value, `--${name}`)])
  );
  return {
    detectorStreamId: parsed.detectorStreamId,
    earlyToleranceNs: millisecondsToNanoseconds(
      parsed.earlyToleranceMs,
      "--early-tolerance-ms"
    ),
    lateToleranceNs: millisecondsToNanoseconds(
      parsed.lateToleranceMs,
      "--late-tolerance-ms"
    ),
    groupBy: parsed.groupBy,
    gates,
    inputs: parsed.inputs,
    jsonOutput: parsed.jsonOutput,
    markdownOutput: parsed.markdownOutput
  };
}

async function loadSessions(inputs, detectorStreamId) {
  const { validators } = await loadValidators();
  const sessions = [];

  for (const [index, input] of inputs.entries()) {
    try {
      const validation = await validateTrace(
        {
          filePath: path.resolve(process.cwd(), input),
          label: `input ${index + 1}`,
          collectRecordTypes: COLLECTED_RECORD_TYPES
        },
        validators
      );
      sessions.push(sessionFromValidatedTrace(validation, detectorStreamId));
    } catch (error) {
      if (error instanceof TraceValidationError) {
        const location = error.line ? ` at line ${error.line}` : "";
        const detail = error.code === "ioFailure" ? "could not be read" : "failed validation";
        throw new MotionEvaluationError(
          error.code,
          `Input ${index + 1}${location}: ${detail}`
        );
      }
      if (error instanceof MotionEvaluationError) {
        throw new MotionEvaluationError(error.code, `Input ${index + 1}: ${error.message}`);
      }
      throw error;
    }
  }

  return sessions;
}

async function writeOutput(target, contents) {
  if (target === "-") {
    process.stdout.write(contents);
    return;
  }
  const outputPath = path.resolve(process.cwd(), target);
  await mkdir(path.dirname(outputPath), { recursive: true });
  const temporaryPath = path.join(
    path.dirname(outputPath),
    `.${path.basename(outputPath)}.${process.pid}.${randomUUID()}.tmp`
  );
  try {
    await writeFile(temporaryPath, contents, { encoding: "utf8", flag: "wx" });
    await rename(temporaryPath, outputPath);
  } catch (error) {
    await rm(temporaryPath, { force: true });
    throw error;
  }
}

export async function run(args) {
  const parsed = parseArguments(args);
  if (parsed.help) {
    process.stdout.write(usage());
    return 0;
  }
  if (parsed.version) {
    process.stdout.write(`${MOTION_EVAL_VERSION}\n`);
    return 0;
  }

  const options = normalizeOptions(parsed);
  if (options.inputs.length === 0) {
    throw new MotionEvaluationError("invalidArguments", "At least one trace is required");
  }
  if (options.jsonOutput === "-" && options.markdownOutput === "-") {
    throw new MotionEvaluationError(
      "invalidArguments",
      "JSON and Markdown cannot both target stdout"
    );
  }
  if (
    options.jsonOutput &&
    options.markdownOutput &&
    options.jsonOutput !== "-" &&
    path.resolve(options.jsonOutput) === path.resolve(options.markdownOutput)
  ) {
    throw new MotionEvaluationError(
      "invalidArguments",
      "JSON and Markdown output paths must be different"
    );
  }
  const inputPaths = new Set(options.inputs.map((input) => path.resolve(process.cwd(), input)));
  for (const output of [options.jsonOutput, options.markdownOutput]) {
    if (output && output !== "-" && inputPaths.has(path.resolve(process.cwd(), output))) {
      throw new MotionEvaluationError(
        "invalidArguments",
        "An output path must not replace an input trace"
      );
    }
  }

  const sessions = await loadSessions(options.inputs, options.detectorStreamId);
  const report = evaluateSessions(sessions, {
    earlyToleranceNs: options.earlyToleranceNs,
    lateToleranceNs: options.lateToleranceNs,
    groupBy: options.groupBy,
    gates: options.gates
  });
  const json = `${JSON.stringify(report, null, 2)}\n`;
  const markdown = renderMarkdown(report);

  try {
    if (!options.jsonOutput && !options.markdownOutput) {
      process.stdout.write(markdown);
    } else {
      if (options.jsonOutput) {
        await writeOutput(options.jsonOutput, json);
      }
      if (options.markdownOutput) {
        await writeOutput(options.markdownOutput, markdown);
      }
    }
  } catch {
    throw new MotionEvaluationError(
      "outputWriteFailure",
      "A requested report could not be written"
    );
  }

  return report.gates.passed ? 0 : 3;
}

if (process.argv[1] && path.resolve(process.argv[1]) === MODULE_PATH) {
  run(process.argv.slice(2))
    .then((exitCode) => {
      process.exitCode = exitCode;
    })
    .catch((error) => {
      if (error instanceof MotionEvaluationError) {
        console.error(`motion-eval: ${error.code}: ${error.message}`);
        process.exitCode = 2;
        return;
      }
      console.error("motion-eval: internalError");
      process.exitCode = 2;
    });
}
