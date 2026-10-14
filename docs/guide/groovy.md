---
title: "Groovy Scripts"
---

# Groovy Scripts

* Contents
{:toc}

## Overview

Like [PME](https://project-ncl.github.io/pom-manipulation-ext/guide/groovy.html) and
[GME](https://project-ncl.github.io/gradle-manipulator/guide/groovy.html), small Groovy scripts can
handle edge cases that need no code change in the tool itself. Scripts run in the order listed, with
the directory of the root build file as the execution root. A script that fails aborts the run
(exit code 10).

> **Security:** scripts are arbitrary code with the privileges of the tool — only run scripts you
> trust. Plain `http://` URLs are rejected; use `https://` or a local `file://` path.

## Configuring Scripts

| Property | Description | Default |
| --- | --- | --- |
| `groovyScripts` | Comma-separated list of Groovy scripts to run (`https://` URLs, `file://` URLs, or file paths) | — |

Example:

```bash
-DgroovyScripts=fix-version.groovy,https://host/shared.groovy
```

## Invocation Stages

Each script declares when it runs by annotating with `@InvocationPoint`:

| Stage | Runs |
| --- | --- |
| `FIRST` | Before any build file is read. Safe to edit build files on disk; the pipeline then aligns the edited files. |
| `PREPARSE` | Alias for `FIRST`, for parity with GME's vocabulary. |
| `LAST` | After versions are computed and (unless `--preview`) rewritten. |
| `ALL` | At both stages; use `getInvocationStage()` to tell them apart. |

## Script API

Scripts extend `BaseScript` and have access to these methods:

| Method | Description |
| --- | --- |
| `File getBaseDir()` | The execution root (directory of the root build file) |
| `File getBuildFile()` | The root Ant build file |
| `Properties getUserProperties()` | The `-D` properties passed on the command line (use this, not `getProperties()`, which is Groovy's own) |
| `InvocationStage getInvocationStage()` | The current stage |
| `Map<String,String> getAlignedVersions()` | Base version → computed publish version (populated in `LAST` only) |
| `Logger getLogger()` | An SLF4J logger |
| `Translator getRESTAPI()` | The Dependency Analyzer client; requires `-DrestURL` |

## Example Script

This script runs before parsing and rewrites a non-standard dev version marker so the pipeline sees
a clean version string:

```groovy
import org.jboss.pnc.antmanipulator.groovy.AMEBaseScript
import org.jboss.pnc.antmanipulator.groovy.BaseScript
import org.jboss.pnc.antmanipulator.groovy.InvocationPoint
import org.jboss.pnc.antmanipulator.groovy.InvocationStage

@InvocationPoint(invocationPoint = InvocationStage.FIRST)
@AMEBaseScript BaseScript ame

def f = ame.getBuildFile()
f.text = f.text.replace('value="1.0-dev"', 'value="1.0"')
```
