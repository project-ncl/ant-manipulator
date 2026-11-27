---
title: "Version Manipulation"
---

# Version Manipulation

* Contents
{:toc}

## Overview

When a project is rebuilt in a cleanroom environment, each artifact must be published under a new
version that identifies it as a rebuild. When version modification is requested (see
[Automatic Version Increment](#automatic-version-increment)), ant-manipulator computes this version by
appending a **rebuild suffix** and a zero-padded serial number to the original version, matching the
convention used by PME and GME:

```
<original>.<suffix>-<serial>
# e.g. 1.2.3  →  1.2.3.redhat-00001
```

The suffix and serial are assigned by the DA service in response to the coordinate lookup; the tool
does not choose a serial unilaterally.

## Disabling Version Modification

`versionModification` is the master switch for the whole process described on this page. It mirrors
PME's property of the same name.

| Property | Description | Default |
| --- | --- | --- |
| `versionModification` | Whether to modify the project version at all | `true` |

When set to `false`, ant-manipulator leaves the project's own version **completely untouched**: it
skips the DA lookup, skips computing any rebuild suffix, and skips the rewrite step entirely. The
build files are left byte-for-byte as they were, and the build publishes under its **declared**
(upstream) version:

```bash
-DversionModification=false
# 1.2.3  →  1.2.3   (no lookup, no suffix, no rewrite)
```

PNC sets `versionModification=false` for build categories meant to publish
under the upstream version, pairing it with a sentinel `versionIncrementalSuffix` that must never be
applied. Because PME honours the flag and short-circuits before touching the version, ant-manipulator
must too — otherwise that sentinel suffix would be used as a versioning part in the published artifacts.

Even with modification disabled, the [PME alignment report](report.html#pme-alignment-report) still
records the execution root's coordinate (at its unchanged version, so `originalGAV` equals the new
version) so PNC/Repour can record and tag the build. The report has no `modules` entries, since
nothing was realigned; combine with `-DreportJsonUnchanged=true` to force it to be written.

## Automatic Version Increment

The rebuild suffix is controlled by the `versionIncrementalSuffix` property.

| Property | Description | Default |
| --- | --- | --- |
| `versionIncrementalSuffix` | Text inserted before the serial number | — (none) |

There is **no default suffix**. As in PME, versioning is opt-in: the suffix must be supplied explicitly,
for example:

```bash
-DversionIncrementalSuffix=redhat
# 2.0.0  →  2.0.0.redhat-00001
```

If no versioning option (`versionIncrementalSuffix` or [`versionOverride`](#version-override)) is
set, ant-manipulator behaves exactly as if
[`versionModification=false`](#disabling-version-modification) had been given: it skips the DA lookup,
the version computation and the rewrite, and leaves the build files untouched. With
`-DreportJsonUnchanged=true` it also writes the
[PME alignment report](report.html#pme-alignment-report) with the execution root at its unchanged
version, exactly as for `versionModification=false`.

An **empty** value (`-DversionIncrementalSuffix=`) is rejected with exit code `10` and an error message,
before any file is read or modified. This deliberately differs from PME, which accepts an empty suffix and
appends a bare build counter (for example `1.2.0` → `1.2.0.3`); ant-manipulator cannot produce or
recognise that form. When `versionModification=false`, an empty suffix is ignored.

### Coordinates sharing a base

The DA lookup version is OSGi-normalised and has `-SNAPSHOT` removed, in the order PME's `RESTCollector` applies
them, so `1.9.11` and `1.9.11-SNAPSHOT` are both looked up as `1.9.11`. Starting versions that share a calculation
base (for example `4.9` and `4.9-SNAPSHOT`) are published under **one** version: the highest existing DA build for
any of them, plus one, as PME's reactor sync does. Previously each literal was computed on its own, so a
`-SNAPSHOT` variant could be published with a different serial than its release, and the report was dropped.

If candidates of one artifact would still be realigned to different versions, the run exits with code `10` before
any build file is modified, and logs the artifact and the conflicting versions.

## Version Override

`versionOverride` mirrors PME's property of the same name. It replaces the version found in the build
files with the one you give as the **starting version**, for every discovered coordinate.

| Property | Description | Default |
| --- | --- | --- |
| `versionOverride` | Starting version used instead of the declared one | — (none) |

```bash
-DversionOverride=1.2.0.Final
# 1.0  →  1.2.0.Final
```

The override is processed as PME does: `-SNAPSHOT` is removed and the result is OSGi-normalised, so
`1.2.0-SNAPSHOT` becomes `1.2.0` and `1.2-beta1` becomes `1.2.0.beta1`. Dev markers
(`versionSuffixStrip`) are not stripped, because you typed the version explicitly.

* **Without** `versionIncrementalSuffix` the normalised override is the final version. No DA lookup
  happens, so `restURL` is not needed.
* **With** `versionIncrementalSuffix` the override is the version DA is asked about, and the suffix is
  applied on top (`1.2.0.Final` → `1.2.0.Final-redhat-00001`). This needs `restURL`; if it is missing the
  run fails with exit code `10` rather than silently publishing without the suffix.

The definition sites of every declared version are rewritten to the result, and the
[alignment report](report.html#pme-alignment-report) still shows the declared version as `originalGAV`.

An **empty** value (`-DversionOverride=`) is rejected with exit code `10` before any file is read or
modified; it is ignored when `versionModification=false`. This is the same deliberate deviation from PME
as for an empty `versionIncrementalSuffix`.

The override only applies to coordinates that exist: ones the build declares, or ones injected with
`alignment.groupId`/`alignment.artifactId`. If there are none, nothing is aligned (a warning is logged).

### Relation to `alignment.version`

| | `versionOverride` | `alignment.version` |
| --- | --- | --- |
| Scope | Every discovered coordinate | The one coordinate named by `alignment.groupId`/`alignment.artifactId` |
| Needs a coordinate to be injected | No | Yes (ignored with a warning otherwise) |
| Precedence | Lower | Higher: wins for its coordinate when both are set |

## Version Increment Padding

The serial number is zero-padded to a fixed width.

| Property | Description | Default |
| --- | --- | --- |
| `versionIncrementalSuffixPadding` | Zero-padding width of the serial number | `5` |

With the default padding of `5`, the first rebuild of `1.0.0` is `1.0.0.redhat-00001`. If a project
has been rebuilt more than 99 999 times (extremely rare), increase the padding accordingly.

## SNAPSHOT Detection

Before the rebuild suffix is applied, ant-manipulator strips any `-SNAPSHOT` qualifier from the
version, matching PME/GME behaviour:

```
1.0.0-SNAPSHOT  →  1.0.0.redhat-00001
```

This is automatic and requires no configuration.

## Suffix Stripping

Some upstream projects append their own dev/pre-release marker directly to the version string in a
way that is not the standard Maven `-SNAPSHOT` qualifier. For example, Apache Tomcat uses
`10.1.0-dev`. Without further configuration, the tool would produce `10.1.0-dev.redhat-00001`,
which is not the correct rebuild version for `10.1.0`.

`versionSuffixStrip` lets you declare such markers so that they are stripped like `-SNAPSHOT`:

| Property | Description | Default |
| --- | --- | --- |
| `versionSuffixStrip` | Comma-separated list of suffixes to strip before applying the rebuild suffix | _(none)_ |

Example — Tomcat:

```bash
-DversionSuffixStrip=dev
# 10.1.0-dev  →  10.1.0.redhat-00001
```

Multiple markers:

```bash
-DversionSuffixStrip=dev,beta,alpha
```

> **Note:** `versionSuffixStrip` is specific to ant-manipulator. It has no equivalent in PME or GME
> because those tools operate on a canonical project model that does not expose this problem.
