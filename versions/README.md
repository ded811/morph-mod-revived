# One source tree, several Minecraft versions

Morph Mod Revived builds for more than one Minecraft version from one set of
sources. This directory holds the differences, and this file is the manual.

## The rule

`src/` and `deds-api/src/` are written for exactly one Minecraft version, the
**canonical** one, named by `mc_canonical` in `gradle.properties` (26.2).
That is the version the IDE sees and the one a plain `./gradlew build`
produces.

Every other version is **derived** from those sources at build time, by two
mechanisms and nothing else:

1. **A rename table.** `versions/mc<version>/renames.txt` lists fully
   qualified class names that merely moved or were renamed between the
   canonical version and this one, one `canonical-name this-version-name`
   pair per line. Every `.java` file is copied with each pair applied, in
   both `a.b.C` and `a/b/C` form. Only whole names match. Resources are never
   filtered.
2. **An overlay.** A file under `versions/mc<version>/<same path as in the
   tree>` replaces the file at that path in the derived copy, and a file with
   no original is added. Overlay files are written in their own version's
   vocabulary and are not passed through the rename table. Keep overlays few
   and thin: move shared logic into a helper the shared tree owns and overlay
   only the part that names the version-specific API.

```
./gradlew build                          # 26.2 (canonical)
./gradlew build -Pmc=26.3                # 26.3, derived
./gradlew runGameTest -Pmc=26.3          # the server gametests on 26.3
./gradlew runClientGameTest -Pmc=26.3    # the client render test on 26.3
```

The derived sources land under `build/versioned/mc<version>/` (and
`deds-api/build/versioned/...`), and that is what javac compiles. Never edit
them; edit the shared tree or the overlay.

## What a version needs

- `versions/mc<version>.properties`: `minecraft_version`, `loader_version`,
  `fabric_api_version`.
- `versions/mc<version>/renames.txt`: may be absent.
- `versions/mc<version>/...`: the overlay, may be empty.

The jar is named `morph-mod-revived-<mod_version>+mc<version>.jar`, and both
`fabric.mod.json` files declare `"minecraft": "~<version>"`, so a jar built
for one version refuses to load on another. Ded's API keeps its own plain
version number (see `gradle.properties`), because the mod depends on it by
range.
