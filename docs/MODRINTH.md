# Morph Mod Revived

**Kill a mob, become the mob.**

An unofficial revival of **iChun's Morph**, based on its classic Minecraft 1.6.4
version and rebuilt for **Minecraft 26.2 and 26.3** on **Fabric**.

The mobs you kill join your collection. Your body twists into their shape while
everyone nearby watches, and you take on their size and their natural
abilities: bats fly, spiders climb walls, blazes shrug off fire, fish breathe
underwater. Your health and attack stay your own, though, and there are no mob
attacks to use, like creeper explosions or blaze fireballs. Switch between your
collected forms whenever you like, or go back to being yourself.

- **Needs** Fabric Loader and [Fabric API](https://modrinth.com/mod/fabric-api).
  Fabric only.
- **Multiplayer:** install it on the server and on every player's game.
- **Beta:** it's playable, but it isn't finished, and it isn't an exact copy of
  the original. Please report bugs on the
  [issue tracker](https://github.com/ded811/morph-mod-revived/issues).

## How it works

1. **Kill a mob you haven't collected yet.** The killing blow has to be yours: a
   hit, an arrow, a thrown trident, a splash potion or TNT you lit all count,
   but a kill by your pet, or by fire, lava or a fall, doesn't. A dark copy of
   the mob breaks apart and flies into you, and you start turning into it
   straight away.
2. **Open the morph selector** with `[` or `]`. It appears on the left side of
   the screen. It isn't a menu, so you can keep walking while you pick. Each mob
   you've collected has a row, with a little 3D model and icons for its
   abilities.
3. **Pick a form** and press Enter. You change over four seconds: your body
   turns dark and speckled, reshapes into the new form, and the new form fades
   in. Everyone nearby sees and hears it. You can't start another change until
   this one has finished.
4. **To go back to yourself,** pick the top row, which has your own name, or the
   top slot of the favourites wheel (see **Controls**).

Different-looking versions of a mob, like sheep colours, slime sizes, horse
coats or villager jobs, are separate morphs, kept together in that mob's row.
So are baby and adult mobs, and name-tagged mobs.

## Controls

| Key | What it does |
| --- | --- |
| `[` or `]` | Open the selector, then move up (`[`) and down (`]`) the list |
| Mouse wheel, with the selector open | Move up and down the list |
| Shift + `[` / `]`, or Shift + mouse wheel | Move sideways through a mob's variants |
| Enter, or left-click | Turn into the highlighted form |
| Esc, or right-click | Close the selector without changing |
| Delete | Remove the highlighted morph for good |
| `` ` `` (the key below Esc), with the selector open | Star or unstar the highlighted morph as a favourite |
| Hold `` ` ``, with the selector closed | Open the favourites wheel: move the mouse toward a form and let go |

- **While the selector is open,** clicks only work the selector: they don't
  swing your hand or use what you're holding. Esc still opens the game menu as
  well, though, and holding Shift also makes you sneak.
- **You can change** the `[`, `]`, Enter, Esc, Delete and `` ` `` keys in Options,
  Controls, Key Binds, under **Morph Mod Revived**. Shift, the mouse, and the
  `` ` `` key that opens the favourites wheel can't be changed.
- **On a non-US keyboard,** the default keys are the ones in the same places as
  `[`, `]` and `` ` `` on a US keyboard; Key Binds shows what they're called on
  yours. On a Mac keyboard, Delete is Fn + delete.
- **The favourites wheel** always has your own form at the top, with your
  starred morphs around it. Letting go near the middle closes it without
  changing.
- **Removing a morph is permanent.** There's no undo; you'd have to kill that
  mob again. You can't remove your own form, the morph you're wearing, or a
  starred favourite (unstar it first).
- **If nothing happens** when you pick or remove a morph, you're probably still
  in the middle of a change.

## Abilities

Every morph gets its mob's natural abilities automatically. Almost all of them
are worked out from the mob itself rather than from a fixed list, so mobs from
other mods usually get fitting abilities too. The selector shows them as small
icons around each morph's picture.

| Ability | What you get | Some mobs that have it |
| --- | --- | --- |
| **Flight** | Fly like in creative mode (double-tap jump). Flying uses up hunger. | bat, bee, allay, parrot, ghast, happy ghast, blaze, phantom, vex |
| **Slow falling** | Drift down slowly, with no fall damage | chicken and parrot only |
| **No fall damage** | Falls never hurt you | bat, cat, ocelot, iron golem, snow golem, magma cube, shulker, breeze |
| **Wall climbing** | Walk into a wall to climb it; sneak to hang on | spider, cave spider |
| **Swimming** | Breathe underwater and swim fast, like Dolphin's Grace | fish, squid, dolphin, turtle, axolotl, frog, guardian, drowned |
| **Fire immunity** | Fire and lava can't hurt you | blaze, ghast, magma cube, strider, wither skeleton, zombified piglin |
| **Step up** | Walk up full blocks without jumping (camels manage 1.5) | horse, donkey, llama, camel, enderman, iron golem, ravager |
| **Poison immunity** | Poison wears off the moment it hits | spiders, and all undead mobs |
| **Wither immunity** | The Wither effect wears off the moment it hits | all undead mobs |
| **Monster disguise** | Most monsters leave you alone (see below) | any monster |
| **Sunburn** | You catch fire in daylight. Any helmet stops it, but the helmet slowly takes damage. | zombie, skeleton, drowned, phantom, stray, bogged |
| **Water weakness** | Water and rain hurt you | enderman, blaze, snow golem, strider |

A few more things that come with the body:

- **You really are the mob's size.** A chicken fits through a one-block gap.
  Big morphs can get stuck: a spider or an iron golem is too wide for a door, and
  an enderman is too tall for a two-block tunnel even when crouching. Tall,
  human-shaped morphs shrink a little when you crouch, but no morph can crawl or
  lie flat.
- **Fish and squid can't breathe on land.** After about 16 seconds out of water
  you start drowning, and you move slowly on land. Dolphins, turtles, axolotls
  and frogs are fine.
- **Flying through water is very slow** for most flyers, like bats, bees and
  parrots. Blazes, ghasts and vexes keep their speed.
- **Striders** float in lava instead of sinking, so you can cross it without
  burning, but only slowly. They're slow on land too.
- **Snow golems** leave a trail of snow behind them.
- **Baby morphs** don't get step-up or sunburn.
- **What doesn't change:** your health and attack damage stay your own, and so
  does your walking speed, apart from the strider and fish slowdowns above.
- **New abilities and your new size arrive when the change finishes.** When you
  change back, you keep the old ones until it finishes, so a bat that changes
  back in mid-air flies for four more seconds and then drops.

## Mobs and other players

- **Monsters leave you alone** while you look like one, and ones already chasing
  you give up. You can even hit them and they won't fight back. Piglins,
  hoglins, zoglins, breezes, wardens and creakings aren't fooled, though: they
  treat you like any other player. Mobs that protect others still go for you:
  a village's iron golem attacks you as a zombie. Pets and player-built golems
  only do that when the server allows PvP.
- **Mobs treat you as what you look like.** As a cat, creepers run from you. As
  a sheep, wild wolves hunt you. As a villager, zombies chase you. As a zombie,
  villagers run away, and may call an iron golem. None of this happens on
  Peaceful or in Creative. When you change form, mobs that were hunting your
  old shape leave you alone.
- **Other players can ride you** while you're a horse, donkey, mule, pig, camel,
  strider, nautilus or happy ghast (skeleton and zombie horses, camel husks and
  zombie nautiluses count too): they right-click you with an empty hand. They
  can't steer, and changing form throws them off. A happy ghast carries up to
  four.
- **Other players can milk you as a cow, get stew from you as a mooshroom, or
  shear you as a sheep**, once every 60 seconds for each item.
- **Kill another player and you can become them,** skin included. If they're
  disguised as a mob, you get that mob instead. A player morph is looks only:
  no abilities, normal player size.
- **While you're a mob,** other players can't see your name tag or find you on
  their locator bar, and when you get hurt you make the mob's hurt sound.

## Good to know

- **Your collection is saved** with your player, separately for each world and
  each server. By default you keep all your morphs when you die, and you come
  back still morphed.
- **Killing the Ender Dragon or the Wither never gives a morph,** and armour
  stands never count. One player can own up to 1,000 morphs.
- **You can sleep in a bed while morphed.** People-shaped morphs lie down;
  others stand on the bed. A server can turn sleeping while morphed off.
- **Don't open your world without the mod installed.** The game throws away
  data from mods it doesn't have, so your collection may be lost the next time
  it saves.
- **It hasn't been tested alongside many other mods yet.** If it clashes with
  one, please report it.

## What you need

- Minecraft **26.2** or **26.3**
- [Fabric Loader](https://fabricmc.net/use/) 0.19.3 or newer (on 26.3 it has
  been tested with 0.19.5, so use that or newer there)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25 or newer

If you download by hand, pick the file for your Minecraft version: its name
ends in `+mc26.2` or `+mc26.3`, and each one only works on that version. You
might see **Ded's API** in your mod list: it comes inside this mod, so you don't
need to download it separately.

On a server, install the mod on the server **and** on every player's game. In
singleplayer, just put it in your mods folder. For LAN games, everyone who joins
needs it too.

## Commands

<details>
<summary>Commands, for operators</summary>

Everything under `/morph` is for operators only; in singleplayer, turn cheats
on. Words in [square brackets] are optional: leave them out to mean yourself.

| Command | What it does |
| --- | --- |
| `/morph help` | Lists the commands |
| `/morph demorph [player]` | Turns a player back into themselves |
| `/morph clear [player]` | Deletes a player's whole collection and favourites. There's no undo. |
| `/morph morphtarget [player]` | Makes the player collect and turn into the mob or player they're looking at, up to 4 blocks away, without killing it. It only works on something they haven't collected yet, and the settings below still apply. |
| `/morph whitelist <name>` / `/morph unwhitelist <name>` | Adds or removes a player on the mod's own list of who can collect morphs (see `whitelistedPlayers` below). This isn't the server whitelist. |

The whitelist commands work straight away, but they rewrite the settings file
from the settings the game loaded when it started, so any changes you've made to
the file since then are lost. Restart before using them. Names match
ignoring capitals.

</details>

## Settings

<details>
<summary>Settings, for server owners and tinkerers</summary>

Settings live in `config/deds_morph/morph.json`, in your game folder (or the
server's folder), and apply to every world you play there. There's no in-game
settings screen.

- **The file starts out as `{}`.** Add only the settings you want to change, by
  name, exactly as spelled below.
- **Restart the game or server** after editing it. Leaving and rejoining a world
  isn't enough.
- **Check your typing.** A misspelled name is ignored. A value written the wrong
  way, like `"true"` in quotes, or `1` and `0` like the original mod used
  instead of `true` and `false`, puts every setting back to its default. If the
  file can't be read at all, for example because of a missing comma or bracket,
  the game replaces it with an empty one. Either way it keeps your old file
  next to it as `morph.json.bak`, so you can fix it and put it back.
- **On a server,** the server's file sets the rules. Each player's own file
  decides their selector options (`sortMorphs` and `allowMorphSelection`). Their
  own `abilities` setting also turns the ability icons, and the movement their
  own game handles (wall climbing, slow falling, fast swimming), on or off. So
  if a server turns `abilities` off, players should turn it off in their own
  file too.

| Setting | Default | What it does |
| --- | --- | --- |
| `abilities` | `true` | Turns all morph abilities on or off. Morphs still change your size either way. |
| `childMorphs` | `true` | Whether baby mobs can be collected |
| `playerMorphs` | `true` | Whether killing a player lets you become them |
| `bossMorphs` | `false` | Allows Wither and Ender Dragon morphs, but killing them still never gives one. An operator can get the Wither with `/morph morphtarget`; the Ender Dragon can't be picked that way. |
| `blacklistedMobs` | `[]` | Mobs nobody can collect, like `["minecraft:creeper", "#minecraft:undead"]`. A `#` means a whole group, here every undead mob. |
| `whitelistedPlayers` | `[]` | Player names, like `["Steve", "Alex"]`. If there are any, only those players can collect new morphs. Empty means everyone can. |
| `loseMorphsOnDeath` | `0` | 0: keep everything when you die. 1: lose your whole collection. 2: lose only the morph you were wearing. |
| `canSleepMorphed` | `true` | Whether you can sleep while morphed |
| `hostileAbilityMode` | `0` | Which monsters ignore you while you're a monster. 0 or 1: all of them. 2: every kind except the one you look like. 3: only the kind you look like. 4: all of them, until you come closer than `hostileAbilityDistanceCheck` blocks. |
| `hostileAbilityDistanceCheck` | `6` | The distance, in blocks, for mode 4 |
| `disableEarlyGameFlight` | `0` | 0: flying morphs can fly. 1 or 2: flying morphs can't fly at all (see **How it differs from the original**). |
| `sortMorphs` | `0` | Selector order. 0: the order you collected them. 1: mobs A to Z. 2: mobs A to Z, and each mob's variants too. 3: most recently worn first (forgotten when you leave the world). |
| `allowMorphSelection` | `true` | Set to `false` to stop the selector and the favourites wheel opening |

These go inside an `"interactions"` or `"ai"` section, as in the example below:

| Section | Setting | Default | What it does |
| --- | --- | --- | --- |
| `interactions` | `mobInteractions` | `true` | Whether other players can milk, stew or shear you |
| `interactions` | `productionItems` | `["minecraft:bucket", "minecraft:bowl", "minecraft:shears"]` | The items they can use on you. Use full item IDs. Your list replaces this one, so include any of these you want to keep. |
| `interactions` | `harvestCooldownTicks` | `1200` | How long before the same item works on you again, in ticks. 20 ticks is one second, so 1200 is 60 seconds. |
| `interactions` | `rideableMorphs` | `true` | Whether other players can ride you |
| `ai` | `aiRelationships` | `true` | Whether mobs treat you as what you look like |
| `ai` | `aiRelationshipsHunt` | `true` | Whether mobs hunt you for what you look like. Turn it off to keep only the running away. |
| `ai` | `aiRelationshipRange` | `24` | How far away, in blocks, a mob can spot you to hunt you. It never reaches past how far that mob normally looks. |

For example, to lose only your worn morph when you die, and stop other players
riding you:

```json
{
  "loseMorphsOnDeath": 2,
  "interactions": {
    "rideableMorphs": false
  }
}
```

</details>

## How it differs from the original

- Abilities are worked out from each mob instead of a fixed list, so mobs from
  other mods don't need extra setup.
- If a morph's mob can't be loaded, for example because you removed the mod it
  came from, the morph waits in your list until the mob is back, instead of
  turning you into a pig.
- **New in this version:** other players can ride, milk or shear you, and mobs
  react to the shape you're in. The original had none of that.
- Some defaults are different. Monsters ignore you while you look like one; the
  original had that off unless a server turned it on, and here the only way to
  turn it off is to turn off all abilities. Baby mobs can be collected, and you
  can sleep while morphed; the original had both off.
- A new kill always turns you into that mob. The original's option to only
  collect it isn't in this version.
- The original could unlock flight only after you reached the Nether or beat the
  Wither. That isn't in this version yet.
- `/morph` needs operator level 2. The original needed level 4.

## Credits

The original **Morph** is by **iChun**: <https://github.com/iChun/Morph>. The
idea, the design, the abilities, the selector, all of the artwork and all six
transformation sounds are his work. This revival brings them to modern
Minecraft; everything good about it is his idea. It isn't made by, endorsed by,
or affiliated with iChun.

## Found a problem?

Bugs, crashes or anything that looks wrong:
<https://github.com/ded811/morph-mod-revived/issues>. Please attach your log:
`logs/latest.log` in your game folder, or the file from `crash-reports` if the
game crashed.

## Source code and licence

The source is at <https://github.com/ded811/morph-mod-revived>, under the
**LGPL-3.0**, the same licence as the original. That includes the bundled
Ded's API. If you share the mod or the API, changed or not, include the licence
and make the source available to the people you give it to, with your changes
if you made any.
