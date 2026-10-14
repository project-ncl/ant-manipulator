---
title: "Version Manipulation"
---

# Version Manipulation

* Contents
{:toc}

## Overview

When a project is rebuilt in a cleanroom environment, each artifact must be published under a new
version that identifies it as a rebuild. ant-manipulator computes this version by appending a
**rebuild suffix** and a zero-padded serial number to the original version, matching the convention
used by PME and GME:

```
<original>.<suffix>-<serial>
# e.g. 1.2.3  →  1.2.3.redhat-00001
```

The suffix and serial are assigned by the DA service in response to the coordinate lookup; the tool
does not choose a serial unilaterally.

## Automatic Version Increment

The rebuild suffix is controlled by the `versionIncrementalSuffix` property.

| Property | Description | Default |
| --- | --- | --- |
| `versionIncrementalSuffix` | Text inserted before the serial number | `redhat` |

Example:

```bash
-DversionIncrementalSuffix=redhat
# 2.0.0  →  2.0.0.redhat-00001
```

This matches the default used by PME and GME and should not normally need to change for Red Hat
rebuilds.

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
