# kzen-sample

Sample integrations for [kzen](https://github.com/alexoooo/kzen). Each top-level directory is an independent
sample, laid out like a repository of its own, so it can be copied out as a template:

```
npx degit alexoooo/kzen-sample/<sample> my-project
```

A sample has one or more **parts**, each a self-contained Maven or Gradle build with its own wrapper. Parts and
samples never reference each other by path: they see each other's artifacts through Maven Local, the same way
they see kzen's.

## Catalogue

| Sample | Parts | Requires | What it shows |
|---|---|---|---|
| [`itch-plugin`](itch-plugin/README.md) | `plugin/` (Maven: core + adapter) | kzen-auto-plugin | a kzen plugin over NASDAQ TotalView-ITCH data: a plain-Java core with no kzen dependency, a thin adapter with readers, Workers and Job templates, installed as a directory of jars |
| [`spring-embed`](spring-embed/README.md) | `frontend/` (Gradle KMP), `backend/` (Maven, Spring Boot) | kzen-auto, `itch-plugin` | kzen-auto workspaces embedded in a Spring Boot host: one runtime, a context and loopback server per workspace, a streaming reverse proxy, host services handed to Workers, a shared memory budget |

## Building

Every build needs a JDK 25 (`JAVA_HOME`). The kzen artifacts come from Maven Local: publish
[kzen-lib](https://github.com/alexoooo/kzen-lib) and [kzen-auto](https://github.com/alexoooo/kzen-auto) first
(`./gradlew publishToMavenLocal` in each). Then build samples in the order of the **Requires** column, and each
sample's parts in the order its README gives, each from its own directory. On a fresh machine:

```
cd itch-plugin/plugin    && ./mvnw -B install
cd spring-embed/frontend && ./gradlew publishToMavenLocal
cd spring-embed/backend  && ./mvnw -B verify
```
