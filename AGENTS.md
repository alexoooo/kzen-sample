# kzen-sample — AI agent guide

Sample integrations for kzen, one independent sample per top-level directory. The catalogue (sample, parts,
requirements, build order) is [README.md](README.md). Read the umbrella's [`../kzen/AGENTS.md`](../kzen/AGENTS.md)
first: its coding standards (`../kzen/docs/CODING_STANDARDS.md`), toolchain pins, file-safety and
stage-new-files rules apply here too. Then read the guide of the sample you are changing.

This repo is **not** part of the kzen Gradle composite, deliberately: samples consume kzen the way an outside
user would, as published artifacts.

## Layout conventions

- **A sample is a template.** Each top-level directory must make sense copied out on its own
  (`npx degit alexoooo/kzen-sample/<sample>`). No root build, no shared parent pom, no shared version catalog.
- **A part is one self-contained build** in its own subdirectory of the sample (`plugin/`, `frontend/`,
  `backend/`, …), even when the sample has only one part, so a second part never forces a reshuffle. Each part
  carries its own wrapper (`mvnw` or `gradlew`) and builds from its own directory.
- **Parts and samples see each other only by Maven coordinates, through Maven Local.** No `relativePath`
  parents, no `includeBuild` between parts, no source or resource paths into another part or sample. The one
  tolerated kind of `../` is a *test-time default* pointing at a kzen checkout beside this repo
  (`itch-plugin`'s `kzen.auto.libs`), and only when it is overridable and the test skips itself if it is absent.
- **All parts of a sample share one version.** Versions that must agree across parts (Kotlin, KSP, kzen) are
  pinned in each part; the sample's docs say which.
- **The sample README lists its parts in build order**, one command each; a sample that needs another sample
  names it in the catalogue's *Requires* column and in its own README.

## Samples

| Sample | Guide |
|---|---|
| `itch-plugin` | [itch-plugin/README.md](itch-plugin/README.md) |
| `spring-embed` | [spring-embed/AGENTS.md](spring-embed/AGENTS.md) |

## Working notes

- Maven parts run through `./mvnw` (Maven 3.9.9), Gradle parts through `./gradlew`, all on a JDK 25
  (`JAVA_HOME`); see the umbrella's Java 25 gotcha for the PATH-`java` trap.
- After changing kzen itself, re-publish it (`publishToMavenLocal` from each kzen sibling) before building a
  sample. Samples see only what Maven Local holds.
- Boot verification instances on spare ports; never the user's 8080 / 18081 dev servers.
