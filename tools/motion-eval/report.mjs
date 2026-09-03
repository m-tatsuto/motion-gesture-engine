import { GESTURES } from "./evaluator.mjs";

function formatDecimal(value, digits = 4) {
  if (value === null) {
    return "—";
  }
  const fixed = value.toFixed(digits);
  if (Number(fixed) === 0) {
    return "0";
  }
  return fixed
    .replace(/(\.\d*?[1-9])0+$/u, "$1")
    .replace(/\.0+$/u, "");
}

function formatLatency(value) {
  return value === null ? "—" : formatDecimal(value / 1_000_000, 3);
}

function formatDuration(value) {
  return formatDecimal(value / 1_000_000_000, 3);
}

function metricRow(label, metric) {
  return [
    label,
    metric.events.truePositives,
    metric.events.falsePositives,
    metric.events.falseNegatives,
    formatDecimal(metric.rates.precision),
    formatDecimal(metric.rates.recall),
    formatDecimal(metric.rates.f1),
    formatDecimal(metric.rates.falsePositivesPerSession),
    formatDecimal(metric.rates.falsePositivesPerNegativeHour),
    formatLatency(metric.latencyNs.p50),
    formatLatency(metric.latencyNs.p95)
  ];
}

function markdownTable(headers, rows) {
  const header = `| ${headers.join(" | ")} |`;
  const separator = `| ${headers.map(() => "---").join(" | ")} |`;
  return [
    header,
    separator,
    ...rows.map((row) => `| ${row.map(String).join(" | ")} |`)
  ].join("\n");
}

function appendMetricTable(lines, overall, byGesture) {
  lines.push(
    markdownTable(
      [
        "Scope",
        "TP",
        "FP",
        "FN",
        "Precision",
        "Recall",
        "F1",
        "FP/session",
        "FP/negative hour",
        "Latency p50 (ms)",
        "Latency p95 (ms)"
      ],
      [
        metricRow("Overall", overall),
        ...GESTURES.map((gesture) => metricRow(gesture, byGesture[gesture]))
      ]
    )
  );
}

function detectorLabel(detector) {
  return `${detector.detectorId} ${detector.detectorVersion} (${detector.configurationIdentity})`;
}

export function renderMarkdown(report) {
  const lines = [
    "# Motion evaluation",
    "",
    `- Sessions: ${report.dataset.sessions}`,
    `- Detector runs: ${report.detectors.map(detectorLabel).join(", ")}`,
    `- Matching: reviewed gesture commit, -${formatLatency(report.matchingPolicy.earlyToleranceNs)} ms / +${formatLatency(report.matchingPolicy.lateToleranceNs)} ms`,
    "",
    "## Event metrics",
    ""
  ];

  appendMetricTable(lines, report.overall, report.byGesture);
  lines.push(
    "",
    "Latency is prediction time minus reviewed gesture-commit time. Percentiles use nearest rank.",
    "",
    "## Negative exposure",
    "",
    `- Reviewed exposure: ${formatDuration(report.overall.negativeExposure.durationNs)} seconds`,
    `- False positives in exposure: ${report.overall.negativeExposure.falsePositives}`
  );

  if (report.overall.negativeWindowClassification) {
    const negative = report.overall.negativeWindowClassification;
    lines.push(
      `- Merged negative windows: ${negative.windows}`,
      `- True negatives: ${negative.trueNegatives}`,
      `- False-positive windows: ${negative.falsePositiveWindows}`,
      `- Specificity: ${formatDecimal(negative.specificity)}`,
      `- Accuracy: ${formatDecimal(negative.accuracy)}`
    );
  } else {
    lines.push(
      "- TN, specificity, and accuracy omitted: no reviewed negative window was declared."
    );
  }

  const selection = report.dataset.annotationSelection;
  lines.push(
    "",
    "## Annotation selection",
    "",
    `- Reviewed gesture commits used as ground truth: ${selection.reviewedGestureCommits}`,
    `- Reviewed negative windows used as exposure: ${selection.reviewedNegativeWindows}`,
    `- Other reviewed annotations ignored: ${selection.ignoredReviewedAnnotations}`,
    `- Synthetic annotations ignored: ${selection.ignoredByProvenance.synthetic}`,
    `- Contributor annotations ignored: ${selection.ignoredByProvenance.contributor}`,
    `- User-report annotations ignored: ${selection.ignoredByProvenance.userReport}`
  );

  if (report.groups.length > 0) {
    lines.push("", "## Groups");
    for (const group of report.groups) {
      const label = Object.entries(group.dimensions)
        .map(([key, value]) => `${key}=${value}`)
        .join(", ");
      lines.push("", `### ${label}`, "", `Sessions: ${group.sessions}`, "");
      appendMetricTable(lines, group.overall, group.byGesture);
    }
  }

  lines.push("", "## Regression gates", "");
  if (!report.gates.configured) {
    lines.push("No gates configured.");
  } else {
    lines.push(
      markdownTable(
        ["Gate", "Rule", "Actual", "Result"],
        report.gates.checks.map((check) => [
          check.name,
          `${check.comparator} ${check.threshold}`,
          check.actual === null ? "unavailable" : formatDecimal(check.actual, 6),
          check.passed ? "PASS" : "FAIL"
        ])
      ),
      "",
      `Overall gate result: ${report.gates.passed ? "PASS" : "FAIL"}`
    );
  }

  return `${lines.join("\n")}\n`;
}
