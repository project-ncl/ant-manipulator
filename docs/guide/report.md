---
title: "Alignment Report"
---

# Alignment Report

* Contents
{:toc}

## Overview

After every run, ant-manipulator emits a JSON report that captures everything it discovered about
the build: every coordinate candidate, how they were grouped into artifacts, which versions are
concrete and which are tokenised (`${property}` / `@TOKEN@`), and — for tokenised versions — the
result of following Ant's substitution chains to a concrete value.

This report is the analogue of PME's `alignmentReport.json`. It is written even when `restURL` is
not set (discovery/report-only mode), making it useful as a diagnostic tool independent of
alignment.

## Output Path {#output-path}

| Property | Description | Default |
| --- | --- | --- |
| `reportJSONOutputFile` | Path of the JSON report file | `alignment-report.json` beside the build file |

By default the file is placed next to the build file passed to `-f`. Override with an absolute or
relative path:

```bash
-DreportJSONOutputFile=/tmp/my-report.json
```

## JSON Structure

The report has four top-level keys:

| Key | Type | Description |
| --- | --- | --- |
| `rootBuildFile` | string | Absolute path of the build file passed to `-f` |
| `generatedAt` | string | ISO-8601 UTC timestamp |
| `summary` | object | Aggregate counts (see below) |
| `artifacts` | array | One entry per distinct `groupId:artifactId` pair |
| `projectVersionCandidates` | array | Coordinates whose GA could not be definitively attributed to a single artifact |

### `summary` object

| Field | Description |
| --- | --- |
| `coordinateCandidates` | Total number of raw coordinate findings before deduplication |
| `artifacts` | Number of distinct `groupId:artifactId` pairs |
| `conflicts` | Number of GAs where two or more incompatible versions were found |
| `projectVersionCandidates` | Number of version candidates that could not be grouped into a confirmed artifact |
| `tokenisedVersions` | Number of distinct version tokens (`${…}` / `@TOKEN@`) seen |
| `tokensResolved` | Tokens that resolved to exactly one concrete value |
| `tokensAmbiguous` | Tokens that resolved to more than one concrete value |
| `tokensUnresolved` | Tokens that could not be resolved to any concrete value |

### `artifacts` array entry

Each entry represents one `groupId:artifactId` and collects all the findings for that pair.

| Field | Description |
| --- | --- |
| `groupId` | Maven group ID |
| `artifactId` | Maven artifact ID |
| `primaryVersion` | The single best version for this artifact (may be a token) |
| `conflict` | `true` if two or more incompatible versions were found |
| `confidence` | Confidence level of the best finding (`HIGH`, `MEDIUM`, `LOW`) |
| `resolvedVersions` | Concrete version strings found directly |
| `tokenisedVersions` | Version token strings found |
| `reconciled` | Reconciliation results for each tokenised version (see below) |
| `findings` | List of individual coordinate findings that contributed to this artifact |

### `reconciled` / reconciliation object

When a version is tokenised, the tool follows Ant's property-substitution chains to find concrete
values. The result is captured in a reconciliation object:

| Field | Description |
| --- | --- |
| `token` | The original token string (`${project.version}`, `@VERSION@`, etc.) |
| `resolved` | List of concrete values the token resolves to |
| `unresolved` | List of intermediate tokens that still could not be resolved |
| `ambiguous` | `true` if `resolved` contains more than one value |
| `fullyResolved` | `true` if `unresolved` is empty |

### Annotated example

```json
{
  "rootBuildFile": "/builds/my-project/build.xml",
  "generatedAt": "2025-03-01T10:00:00.000000000Z",
  "summary": {
    "coordinateCandidates": 3,
    "artifacts": 2,
    "conflicts": 0,
    "projectVersionCandidates": 1,
    "tokenisedVersions": 1,
    "tokensResolved": 1,
    "tokensAmbiguous": 0,
    "tokensUnresolved": 0
  },
  "artifacts": [
    {
      "groupId": "com.example",
      "artifactId": "my-lib",
      "primaryVersion": "@VERSION@",
      "conflict": false,
      "confidence": "HIGH",
      "resolvedVersions": [],
      "tokenisedVersions": ["@VERSION@"],
      "reconciled": [
        {
          "token": "@VERSION@",
          "resolved": ["1.2.3"],
          "unresolved": [],
          "ambiguous": false,
          "fullyResolved": true
        }
      ],
      "findings": [
        {
          "groupId": "com.example",
          "artifactId": "my-lib",
          "version": "@VERSION@",
          "sourceKind": "POM_TEMPLATE",
          "confidence": "HIGH",
          "sourceFile": "/builds/my-project/src/maven/my-lib.pom",
          "detail": "pom template",
          "reconciled": {
            "token": "@VERSION@",
            "resolved": ["1.2.3"],
            "unresolved": [],
            "ambiguous": false,
            "fullyResolved": true
          }
        }
      ]
    }
  ],
  "projectVersionCandidates": []
}
```

## Programmatic Access

The report is produced by `AlignmentReport` and serialised by `AlignmentReportJson` in the `core`
module (`org.jboss.pnc.antmanipulator.report`). If you need to consume the report programmatically
rather than parsing the JSON directly, use the `AlignmentReport.build(...)` factory method after
running the pipeline stages manually.
