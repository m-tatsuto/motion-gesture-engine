import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(TEST_DIR, "../../..");
const CLI_PATH = path.join(PROJECT_ROOT, "tools", "motion-eval", "cli.mjs");
const FULL_TRACE = path.join(PROJECT_ROOT, "fixtures", "v1", "valid", "full.mge.jsonl");
const PREDICTION_ONLY_TRACE = path.join(
  PROJECT_ROOT,
  "fixtures",
  "replay",
  "legacy-gravity-threshold-v1.with-predictions.mge.jsonl"
);

function runCli(args) {
  return spawnSync(process.execPath, [CLI_PATH, ...args], {
    cwd: PROJECT_ROOT,
    encoding: "utf8"
  });
}

test("JSON output contains aggregate results without private trace metadata", () => {
  const result = runCli(["--json", "-", FULL_TRACE]);

  assert.equal(result.status, 0, result.stderr);
  const report = JSON.parse(result.stdout);
  assert.deepEqual(report.overall.events, {
    truePositives: 1,
    falsePositives: 0,
    falseNegatives: 0
  });
  assert.equal(report.dataset.annotationSelection.ignoredByProvenance.userReport, 1);

  for (const privateValue of [
    PROJECT_ROOT,
    "00000000-0000-4000-8000-000000000002",
    "iPhone16,2",
    "osMajorVersion",
    "exactModel",
    "detectorStreamId",
    "missedGesture"
  ]) {
    assert.equal(result.stdout.includes(privateValue), false, `leaked ${privateValue}`);
  }
});

test("Markdown is the default human-readable output", () => {
  const result = runCli([FULL_TRACE]);

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /^# Motion evaluation/mu);
  assert.match(result.stdout, /\| Overall \| 1 \| 0 \| 0 \|/u);
  assert.match(result.stdout, /User-report annotations ignored: 1/u);
  assert.equal(result.stdout.includes("iPhone16,2"), false);
  assert.equal(result.stdout.includes(FULL_TRACE), false);
});

test("JSON and Markdown reports can be written together", async (context) => {
  const outputDirectory = await mkdtemp(path.join(tmpdir(), "motion-eval-test-"));
  context.after(async () => {
    await rm(outputDirectory, { recursive: true, force: true });
  });
  const jsonPath = path.join(outputDirectory, "nested", "report.json");
  const markdownPath = path.join(outputDirectory, "nested", "report.md");

  const result = runCli([
    "--json",
    jsonPath,
    "--markdown",
    markdownPath,
    "--group-by",
    "detectorVersion,platformFamily",
    FULL_TRACE
  ]);

  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, "");
  const report = JSON.parse(await readFile(jsonPath, "utf8"));
  const markdown = await readFile(markdownPath, "utf8");
  assert.deepEqual(report.groups[0].dimensions, {
    detectorVersion: "1.0.0",
    platformFamily: "ios"
  });
  assert.match(markdown, /detectorVersion=1\.0\.0, platformFamily=ios/u);
});

test("a failed regression gate still emits JSON and exits 3", () => {
  const result = runCli([
    "--json",
    "-",
    "--min-recall",
    "0.5",
    PREDICTION_ONLY_TRACE
  ]);

  assert.equal(result.status, 3, result.stderr);
  const report = JSON.parse(result.stdout);
  assert.equal(report.gates.configured, true);
  assert.equal(report.gates.passed, false);
  assert.equal(report.gates.checks[0].reason, "metricUnavailable");
});

test("gzip input remains detectable when private paths are replaced by input ordinals", async (context) => {
  const outputDirectory = await mkdtemp(path.join(tmpdir(), "motion-eval-gzip-test-"));
  context.after(async () => {
    await rm(outputDirectory, { recursive: true, force: true });
  });
  const gzipPath = path.join(outputDirectory, "private-session.mge.jsonl.gz");
  await writeFile(gzipPath, gzipSync(await readFile(FULL_TRACE), { mtime: 0 }));

  const result = runCli(["--json", "-", gzipPath]);

  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).overall.events.truePositives, 1);
  assert.equal(result.stdout.includes(gzipPath), false);
});

test("validation failures identify an input ordinal without echoing its path", () => {
  const privatePath = path.join(PROJECT_ROOT, "private-user-name.mge.jsonl");
  const result = runCli([privatePath]);

  assert.equal(result.status, 2);
  assert.match(result.stderr, /motion-eval: ioFailure: Input 1: could not be read/u);
  assert.equal(result.stderr.includes(privatePath), false);
  assert.equal(result.stdout, "");
});

test("an output path cannot overwrite an input trace", async () => {
  const before = await readFile(FULL_TRACE, "utf8");
  const result = runCli(["--json", FULL_TRACE, FULL_TRACE]);

  assert.equal(result.status, 2);
  assert.match(result.stderr, /output path must not replace an input trace/u);
  assert.equal(await readFile(FULL_TRACE, "utf8"), before);
});
