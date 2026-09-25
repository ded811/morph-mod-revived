# One source tree, two loaders, several Minecraft versions

Morph Mod Revived builds for two mod loaders (Fabric and NeoForge) and for
more than one Minecraft version from one set of sources. This directory holds
the differences between Minecraft versions, and this file is the manual. The
build's own error messages point here.

## The source trees

Six trees, each with the usual `src/main/` (and, for the mod, `src/gametest/`):

| Tree | What it holds | Compiled by |
| --- | --- | --- |
| `common/` | the mod's shared code, resources, assets and server gametests | `:fabric` and `:neoforge` |
| `fabric/` | the Fabric entrypoints, `fabric.mod.json`, the Fabric client test | `:fabric` |
| `neoforge/` | the NeoForge entrypoints, `neoforge.mods.toml`, the NeoForge-only mixins, the NeoForge test mod | `:neoforge` |
| `deds-api/common/` | Ded's API core and its loader-neutral internals | `:deds-api-fabric` and `:deds-api-neoforge` |
| `deds-api/fabric/` | Ded's API Fabric backend | `:deds-api-fabric` |
| `deds-api/neoforge/` | Ded's API NeoForge backend | `:deds-api-neoforge` |

`common/` and `deds-api/common/` are plain shared source, not Gradle
projects: each loader project compiles them itself. Nothing in them, or in
their overlays here, may name `net.fabricmc` or `net.neoforged`
(`checkLoaderNeutral` fails the build if it does). The one exception is the
shared gametests, which may name the two Fabric gametest API types every test
class uses; the NeoForge test mod serves those names with stand-ins of its
own.

## The rule

All six trees are written for exactly one Minecraft version, the
**canonical** one, named by `mc_canonical` in `gradle.properties` (26.2).
That is the version the IDE sees and the one a plain `./gradlew build`
produces.

Every other version is **derived** from those sources at build time, by two
mechanisms and nothing else:

1. **A rename table.** `versions/mc<version>/renames.txt` lists classes that
   merely moved or were renamed between the canonical version and this one,
   one `canonical-name this-version-name` pair per line (`#` starts a
   comment, blank lines are ignored). A pair is either:
   - **fully qualified** (`a.b.C a.b.D`): applied in both `a.b.C` and
     `a/b/C` form, so imports, code and mixin descriptor strings all follow;
   - **a bare simple name** (`C D`), for a class that was renamed in place:
     it matches only a whole identifier, never part of a longer one. The one
     entry today, `EnderMan Enderman`, is of this kind.

   Longer names are applied first, and a name never matches when the next
   character could continue an identifier. Only `.java` files are rewritten;
   resources are never filtered.
2. **An overlay.** A file at `versions/mc<version>/<same path as in the
   tree>` replaces the file at that path in the derived copy, and a file with
   no original is added. For example,
   `versions/mc26.3/common/src/main/java/com/deds/morph/client/McCompat.java`
   replaces `common/src/main/java/com/deds/morph/client/McCompat.java` on
   26.3. Overlay files are written in their own version's vocabulary and are
   not passed through the rename table. Keep overlays few and thin: move
   shared logic into a helper the shared tree owns and overlay only the part
   that names the version-specific API.

The canonical version may have an overlay too (`versions/mc26.2/`, used
today by `neoforge/`). It may only **add** files the tree does not have,
never replace one, because the canonical build compiles the tree and its
overlay side by side. It holds files that exist for the canonical version
only, such as NeoForge 26.2's own first-person hand mixin.

## What the build checks

`checkVersionedSources` runs on every build and fails it when:

- a `versions/mc<version>/` directory has no `versions/mc<version>.properties`
  beside it, or a line of its `renames.txt` does not parse;
- a file in the canonical version's overlay has the same path as a file in
  the tree (that would be a duplicate class);
- an overlay file is not under a source root that some project really
  compiles. An overlay has to mirror the path of a file in one of the six
  trees (for example `versions/mc26.3/neoforge/src/main/...`); one left at a
  path nothing reads would otherwise be ignored without a word;
- a Java overlay file for a derived version still names, in code, a canonical
  class that the rename table rewrites. Use the derived version's name.
  Comments are not checked, so an overlay may explain a rename.

The third rule needs every project configured, since each project registers
its own source roots. If Gradle's configure-on-demand leaves some projects
out (for example a `:fabric:build` with it switched on), that one rule is
skipped with a message saying so, and the others still run.

## Commands

```
./gradlew build                                  # 26.2 (canonical), both loaders
./gradlew build -Pmc=26.3                        # 26.3, derived, both loaders
./gradlew :fabric:build -Pmc=26.3                # one loader only
./gradlew :neoforge:build -Pmc=26.3
./gradlew :fabric:runGameTest -Pmc=26.3          # Fabric server gametests
./gradlew :neoforge:runGameTestServer -Pmc=26.3  # NeoForge server gametests
./gradlew :fabric:runClientGameTest -Pmc=26.3    # Fabric client test
./gradlew :neoforge:runClientGameTest -Pmc=26.3  # NeoForge client test
```

`./gradlew build` builds both loaders and runs both loaders' server
gametests, plus the build checks. The client tests open a game window, so
`build` never runs them. Always give the project path for a test task: a bare
`runGameTest` exists only in `:fabric`, so it quietly skips NeoForge, and a
bare `runClientGameTest` starts both clients, one after the other.

For a derived version, the derived sources land under
`<project>/build/versioned/mc<version>/<tree path>/`, one copy per project
that compiles that tree (for example
`fabric/build/versioned/mc26.3/common/src/main/java/`), and that is what
javac compiles. Never edit them; edit the tree or the overlay.

Run directories follow the same rule on both loaders, so a world saved by
one game version is never opened by another: `./gradlew :fabric:runClient`
and `./gradlew :neoforge:runClient` use `<project>/run/` for the canonical
version and `<project>/run-mc<version>/` for any other. Fabric's server
gametests keep their world between runs, in `fabric/build/run/gameTest/`
(or `gameTest-mc<version>/`); NeoForge's make a fresh world every run. Both
client tests start from an empty directory every run.

## What a version needs

- `versions/mc<version>.properties` with four keys:
  - `minecraft_version`: must equal the version in the file name;
  - `loader_version`: Fabric Loader;
  - `fabric_api_version`: Fabric API;
  - `neoforge_version`: NeoForge.

  All four are required even for a one-loader build: the root build checks
  them before any loader project is set up.
- `versions/mc<version>/renames.txt`: may be absent.
- `versions/mc<version>/<tree path>/...`: the overlay, may be empty.

## What comes out

| Jar | Where |
| --- | --- |
| `morph-mod-revived-fabric-<mod_version>+mc<version>.jar` | `fabric/build/libs/` |
| `morph-mod-revived-neoforge-<mod_version>+mc<version>.jar` | `neoforge/build/libs/` |

Each carries Ded's API inside it: `META-INF/jars/deds-api-<api_version>.jar`
in the Fabric jar, and
`META-INF/jarjar/com.deds.deds-api-neoforge-<api_version>.jar` in the
NeoForge jar. The standalone API jars are built too, as
`deds-api/fabric/build/libs/deds-api-<api_version>.jar` and
`deds-api/neoforge/build/libs/deds-api-neoforge-<api_version>.jar`.

A jar built for one Minecraft version refuses to load on another. Both
`fabric.mod.json` files declare `"minecraft": "~<version>"`, and both
`neoforge.mods.toml` files declare `versionRange = "[<version>,<next>)"` for
`minecraft` and `neoforge`, where `<next>` is the version with its last
number raised by one (the 26.3 jar says `[26.3,26.4)`). The build fills all
of these in for the version it is building.

Ded's API keeps its own plain version number (`api_version` in
`gradle.properties`), because the mod depends on it by range:
`">=2.0.0 <3.0.0"` in `fabric.mod.json` and `[2.0.0,3.0.0)` in
`neoforge.mods.toml`.
