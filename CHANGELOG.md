# Changelog

## 0.2.0-beta

**Morph Mod Revived now runs on NeoForge too**, for **Minecraft 26.2 and 26.3**,
with the same features as on Fabric: the same morphs, abilities, selector,
favourites wheel, commands and settings.

**Pick the jar for your loader and your Minecraft version:**

| | Minecraft 26.2 | Minecraft 26.3 |
| --- | --- | --- |
| Fabric | `morph-mod-revived-fabric-0.2.0-beta+mc26.2.jar` | `morph-mod-revived-fabric-0.2.0-beta+mc26.3.jar` |
| NeoForge | `morph-mod-revived-neoforge-0.2.0-beta+mc26.2.jar` | `morph-mod-revived-neoforge-0.2.0-beta+mc26.3.jar` |

Each one only loads on the loader and the version in its name.

**You need:**

- On Fabric: Fabric Loader 0.19.3 or newer for 26.2, or 0.19.5 or newer for
  26.3, and Fabric API
- On NeoForge: NeoForge for your Minecraft version (tested with 26.2.0.75,
  26.2.0.88, 26.3.0.7-beta and 26.3.0.16-beta). Fabric API isn't needed.
- Java 25 or newer

Ded's API is still bundled inside the mod jar, so there's nothing else to
download. On a server, install the mod on the server and on every player's
game, with the same loader on both.

**Two things work differently on NeoForge:**

- **A world keeps its morphs on one loader only.** Fabric and NeoForge save
  morph collections in different places, so a world moved from Fabric to
  NeoForge, or back, loses everyone's collection.
- **A player running NeoForge without the mod can't join a server that has
  it.** On Fabric they can join, but they don't see any of the mod. With the
  mod installed, you can still join servers that don't have it, on both
  loaders.

**Nothing changed for Fabric players** apart from the mod's description text.
Your collections and settings carry over from 0.1.0-beta as they are.

Please report bugs on the
[issue tracker](https://github.com/ded811/morph-mod-revived/issues).

**Source code:** <https://github.com/ded811/morph-mod-revived>, under the
LGPL-3.0, the same licence as the original.

## 0.1.0-beta

The first release, for **Minecraft 26.2 and 26.3** on **Fabric**.

**Pick the jar for your Minecraft version.** `morph-mod-revived-0.1.0-beta+mc26.2.jar`
is for 26.2 and `morph-mod-revived-0.1.0-beta+mc26.3.jar` is for 26.3. Each one
only loads on the version in its name. The two jars have the same features.

**What it does:** kill a mob and you can become it. The mobs you kill join
your collection of morphs, and your body changes into the new shape over
a few seconds while other players watch. Open the selector to switch between
your collected forms, or go back to your own skin at any time. You get the
shape's size and its passive abilities, like flight, wall-climbing, water
breathing, fire immunity and burning in sunlight. It works on mobs from
other mods too, and on other players.

**This is a beta, and an unofficial revival.** It's playable, but not finished,
and it isn't a 1:1 copy of the 1.6.4 original:

- Abilities are worked out from each mob's own properties instead of a fixed
  list, so modded mobs get sensible abilities too.
- If a morph's mob can't be loaded, for example because its mod was removed,
  the morph waits in your list until the mob is back, instead of turning you
  into a pig.
- Baby mobs can be collected, and you can sleep in a bed while morphed; the
  original had both off by default. A few other details differ too.

Please report bugs on the
[issue tracker](https://github.com/ded811/morph-mod-revived/issues).

**You need:**

- Fabric Loader 0.19.3 or newer for 26.2, or 0.19.5 or newer for 26.3
- Fabric API
- Java 25 or newer

Ded's API is bundled inside the mod jar, so there's nothing else to download.

**Credit:** the original Morph is by iChun. The idea, the design, the
abilities, the selector, all of the artwork and all of the transition sounds
are his work. This revival isn't made by, endorsed by, or affiliated with him.

**Source code:** <https://github.com/ded811/morph-mod-revived>, under the
LGPL-3.0, the same licence as the original.
