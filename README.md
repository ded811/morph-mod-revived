# Morph Mod Revived

**Status: 0.1.0-beta — playable, not finished. Expect bugs and changes.**

An unofficial revival of **iChun's Morph** for **Minecraft 26.2 and 26.3** on
**Fabric**.

Kill a mob, become the mob. Every living creature you slay joins your
collection of morphs, and your body twists into the new shape over a few
seconds — bat, blaze, creeper, anything — while other players watch it happen.
Open the selector to flip between your collected forms, or return to your own
skin at any time. You inherit the shape's hitbox and its passive abilities:
flight, wall-climbing, water breathing, fire immunity, sunburn. It works on
mobs from other mods too, and on other players.

Not made by, endorsed by, or affiliated with iChun.

## Credit

The original **Morph** is by **iChun** — <https://github.com/iChun/Morph>
(branch `legacy` is the 1.6 line this port studied). The idea, the design, the
ability set, the selector, **all of the artwork** and all six transition
sounds are his work. This project ports that work to a modern Minecraft;
everything good about it is his idea.

## Install

1. [Fabric Loader](https://fabricmc.net/use/): 0.19.3+ for Minecraft 26.2, 0.19.5+
   for 26.3
2. [Fabric API](https://modrinth.com/mod/fabric-api): 0.155.2+26.2 for 26.2,
   0.161.0+26.3 for 26.3
3. Java 25 or newer
4. Drop the mod jar for YOUR Minecraft version in your `mods` folder:
   `morph-mod-revived-<version>+mc26.2.jar` or `...+mc26.3.jar`. Each one
   only loads on the version in its name.

**Ded's API** (`deds_api`) is required and is bundled inside the mod jar, so
there is nothing extra to download. If you already run another of my revival
mods, they share the same API and Fabric will sort out which copy to load.

## Build

```
./gradlew build             # Minecraft 26.2
./gradlew build -Pmc=26.3   # Minecraft 26.3
```

Needs JDK 25. The jar lands in `build/libs/`. One set of sources builds both
versions; `versions/README.md` explains how.

## Licence

**LGPL-3.0-only.** The original is LGPL-3.0, so this port is too. The
bundled Ded's API contains none of the original's code, but its author has put
it under the LGPL-3.0 as well. The licence is two documents:
[`LICENSE`](LICENSE) (the LGPL-3.0) and [`LICENSE.GPL`](LICENSE.GPL) (the
GPL-3.0 it incorporates). See [`NOTICE`](NOTICE) for attribution, the
statement of changes, the asset position, and who holds the copyright in which
part.

If you redistribute a built jar, keep `LICENSE`, `LICENSE.GPL` and `NOTICE`
with it and make the matching source available. A link to this repository
does that for an unchanged jar; if you changed anything, say so with a date
and publish your changed source. Shipping only Ded's API inside your own mod
does not put your mod under the LGPL; see NOTICE section 6.
