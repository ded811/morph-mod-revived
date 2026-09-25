# Changelog

## 0.1.0-beta

The first release, for **Minecraft 26.2 and 26.3** on **Fabric**.

**Pick the jar for your Minecraft version.** `morph-mod-revived-0.1.0-beta+mc26.2.jar`
is for 26.2 and `morph-mod-revived-0.1.0-beta+mc26.3.jar` is for 26.3. Each one
only loads on the version in its name. The two jars have the same features.

**What it does:** kill a mob and you can become it. Every creature you kill
joins your collection of morphs, and your body changes into the new shape over
a few seconds while other players watch. Open the selector to switch between
your collected forms, or go back to your own skin at any time. You get the
shape's size and its passive abilities, like flight, wall-climbing, water
breathing, fire immunity and burning in sunlight. It works on any living
creature, including modded mobs and other players.

**This is a beta, and an unofficial revival.** It's playable, but not finished,
and it isn't a 1:1 copy of the 1.6.4 original:

- Abilities are worked out from each mob's own properties instead of a fixed
  list, so modded mobs get sensible abilities too.
- If a morph's mob can't be loaded, for example because its mod was removed,
  the morph waits in your list until the mob is back, instead of turning you
  into a pig.
- Several config defaults are different from the original.

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
