# How the NeoForge build matches Fabric

Morph Mod Revived is one mod built for two loaders. The Fabric build came
first (0.1.0-beta) and is the reference: the NeoForge build has to behave
the same, and where it does not, that is a bug unless this file lists it as
a known difference.

The shared code (`common/`, `deds-api/common/`) names no loader. Everything
NeoForge-specific lives in `neoforge/` (the mod's entrypoints, manifest,
NeoForge-only mixins and test mod) and `deds-api/neoforge/` (Ded's API's
NeoForge backend). [`versions/README.md`](../versions/README.md) explains the
layout and how the Minecraft versions are derived. This file explains the
places where matching Fabric took more than calling a NeoForge event.

## The events Ded's API bridges

The rule: where NeoForge posts an event at the point Fabric API fires, with
the same values, Ded's API uses it. Where it does not, Ded's API mirrors
Fabric API's own mixin at the same injection point
(`deds-api/neoforge/src/main/resources/deds_api.neoforge.mixins.json`). The
bridging is in
`deds-api/neoforge/src/main/java/com/deds/api/neoforge/DedsApiNeoForge.java`,
and the mixins are in `.../neoforge/mixin/`.

| Ded's event | On NeoForge | Why not the obvious NeoForge event |
| --- | --- | --- |
| `ServerEvents.STARTED` | `ServerStartedEvent` | (it matches: posted right after `initServer`, a few statements before Fabric's hook, with nothing observable in between) |
| `ServerEvents.TICK_END`, `PLAYER_TICK_END` | `ServerTickEvent.Post`, then the same loop over the player list as on Fabric | `PlayerTickEvent` fires inside each player's own tick, on both sides, not after the whole server tick |
| `ServerEvents.STOPPING` | `MinecraftServerStoppingMixin`: HEAD of `MinecraftServer.stopServer`, like Fabric | `ServerStoppingEvent` is only posted on a normal exit; `stopServer` also runs after a crash or a failed start |
| `CombatEvents.PLAYER_KILLED_LIVING`, mob victim | `LivingEntityKillMixin`: wraps the `killedEntity` call in `LivingEntity.die`, like Fabric | `LivingDeathEvent` fires at the start of `die`, before the death is final, and a later listener can still cancel it; Morph removes the victim, so it must act on a death that happened |
| `CombatEvents.PLAYER_KILLED_LIVING`, player victim | `ServerPlayerKillMixin`: at `getKillCredit` in `ServerPlayer.die`, like Fabric | `ServerPlayer.die` never calls `LivingEntity.die`. Fabric API's extra `killedEntity` call here is its own fix of vanilla and is not copied |
| `InteractionEvents.USE_ENTITY`, server | `ServerGamePacketListenerUseEntityMixin`: at `getItemInHand` in `handleInteract`, like Fabric | `PlayerInteractEvent.EntityInteract` fires later (after the spectator and item checks), and a cancelled one still swings the arm and awards the advancement |
| `InteractionEvents.USE_ENTITY`, client | `MinecraftUseEntityMixin`: at the `MultiPlayerGameMode.interact` call in `startUseItem`, like Fabric | on the client, `EntityInteract` fires after the interact packet was already sent, so a FAIL could not stop it |
| Commands | `RegisterCommandsEvent` | (it matches: posted where Fabric's command callback fires, with the same three values) |
| Client end of tick | `ClientTickEvent.Post` | (it matches, except that NeoForge does not post it before the first resource load finishes, when there is no world) |
| `PlayerEvents.ATTACK_BLOCK`, `USE_BLOCK` | not bridged yet | see "What Ded's API does not do on NeoForge yet" |

Minecraft 26.3 changed the arm-swing API, so the client use-entity mixin has
a 26.3 twin in `versions/mc26.3/deds-api/neoforge/`. Where Fabric API reads a
local variable by name, the NeoForge mixins read it by type, because a
production NeoForge jar is not guaranteed to carry local variable names.

## Private player data (the morph list)

Morph stores two things on each player: `deds_morph:worn`, the morph a player
is wearing, which every client needs so it can draw them, and
`deds_morph:state`, the collected list and favourites, which only the owner's
client may ever receive. Ded's API calls the second kind `TARGET_ONLY`.

Fabric applies its "owner only" rule on every sync. NeoForge does not: its
initial sync (on login, when another player starts tracking you, on respawn
and on dimension change) never asks the sync handler who may receive the
data, so a plain predicate would send your list to everyone who comes near
you. So `NeoForgeModContext` (in `deds-api/neoforge/.../neoforge/`) gives
`TARGET_ONLY` data its own sync handler:

- it allows only the owner to receive updates;
- it writes nothing during an initial sync, so NeoForge leaves the type out
  of that packet altogether;
- right after each of the owner's initial syncs (`PlayerLoggedInEvent`,
  `PlayerRespawnEvent`, `PlayerChangedDimensionEvent`), `DedsApiNeoForge`
  sends the owner's own copy again as an ordinary update, which does ask the
  handler, so it reaches the owner and nobody else.

The server tests check that no other player can receive the list. That the
owner gets it back at login, after respawning and after changing dimension is
checked by the NeoForge client test (its last scenario), not by the server
tests.

The rest of `NeoForgeModContext` follows Fabric point by point: the saved
fields have the same names and layout, a respawned player keeps the same
object, reading a player that has no data returns the default without storing
it, and setting a value syncs only when it changed.

## Networking

Each message is its own payload type, `<modid>:<name>`, as on Fabric
(`NeoForgeMessageType.java`). NeoForge only accepts payload types in
`RegisterPayloadHandlersEvent`, so registrations are queued until then, and
one made later throws.

- The payloads are registered **optional**, so NeoForge's channel check never
  refuses a connection because of them.
- A message for a client that does not have the channel is skipped, which is
  what Fabric's send amounts to. NeoForge would throw instead, and gametest
  mock players have no channels at all.
- A message from a client to a server that does not have the channel is
  dropped (`NeoForgeClientNet.send`). NeoForge would throw on the key press.
- NeoForge refuses to start unless every server-to-client payload has a
  client handler, so each one gets a small dispatcher that hands the message
  to whatever listener the mod set.

The result: a NeoForge player **with** the mod can join servers without it,
as on Fabric. A player running NeoForge **without** the mod cannot join a
NeoForge server that has it. That is not the payloads but NeoForge's registry
sync: every attachment type that syncs goes into the synced registry
`neoforge:synced_attachment_types`, and a client missing entries the server
has is disconnected. On Fabric such a client joins and sees nothing of
Morph. This is one of the two differences players can notice.

## The client

`neoforge/src/main/java/com/deds/morph/neoforge/MorphNeoForgeClient.java` and
`deds-api/neoforge/.../client/DedsApiNeoForgeClient.java`:

- **Key bindings.** NeoForge loads saved bindings only for mappings
  registered in `RegisterKeyMappingsEvent`. Ded's API queues every binding
  and registers them all from one listener at `EventPriority.LOWEST` (after
  other mods' own listeners), under a real controls category per mod
  (`key.category.<modid>.main`, as on Fabric), with the plain vanilla
  mapping constructor, so NeoForge treats them as ordinary vanilla mappings.
  A binding asked for after that event throws, as Fabric's does once the
  options exist.
- **The selector and the favourites wheel.** Fabric API draws Morph's two
  HUD elements from a wrapper around `SubtitleOverlay.extractRenderState`,
  after the subtitles. `SubtitleOverlayHudMixin` wraps the same method and
  draws them in the same order, so they hide with F1 and draw over toasts and
  the F3 screen, as on Fabric. A NeoForge `registerAboveAll` layer would do
  neither.
- **The crosshair**, hidden while the wheel is open: `wrapLayer` on NeoForge's
  crosshair layer (Fabric replaces the same `minecraft:crosshair` element),
  keeping the layer's own "HUD visible" check.
- **The acquisition effect** in the world: `SubmitCustomGeometryEvent`, which
  hands over the same pose stack, submit node collector and level render
  state as Fabric's `LevelRenderEvents.COLLECT_SUBMITS`. It fires just before
  the gizmo submits rather than after them; Morph submits no outline, so
  nothing can tell.
- **The first-person morph hand.** NeoForge 26.2 adds a 6-argument
  `renderRightHand`/`renderLeftHand` and turns vanilla's 5-argument pair into
  a shim that nothing calls, so a hook on the 5-argument methods would apply
  and never run. On NeoForge 26.2 the hand hook is
  `versions/mc26.2/neoforge/.../AvatarRendererNeoForgeMixin.java`, on the
  6-argument methods. NeoForge 26.3 and Fabric (both versions) use the shared
  5-argument `AvatarRendererMixin`. Each version's
  `deds_morph.neoforge.hand.mixins.json` (under `versions/mc26.2/neoforge/`
  and `versions/mc26.3/neoforge/`) lists exactly one of the two.
- **Mouse input** needs nothing: the shared `MouseHandlerMixin` applies
  unchanged.

## Vanilla behaviour NeoForge changed

NeoForge patches Minecraft itself, and in a few places Morph relied on the
vanilla answer. `MorphLoader` (`common/src/main/java/com/deds/morph/`) is the
seam: its defaults are vanilla, which is what Fabric runs, and
`MorphNeoForge` installs `NeoForgeMorphLoader` before the mod starts.

- **How far villagers keep from a mob.** NeoForge moved the distances into
  the data map `neoforge:acceptable_villager_distances` and reads it before
  the vanilla table. `NeoForgeMorphLoader` does the same, in the same order.
  NeoForge's default map holds the vanilla table's entries, so unless a
  datapack changes it the answer is Fabric's.
- **Shears.** NeoForge removed the shears branch from the sheep, snow golem
  and bogged `mobInteract` and shears through `IShearable` in the shears item
  instead. Morph calls `mobInteract` on its dummy copy of the mob directly,
  so on NeoForge shearing a sheep-shaped player did nothing. When the dummy
  declines, the player holds shears and the mob is `IShearable`,
  `NeoForgeMorphLoader` runs the same item path. A sheep that cannot be
  sheared yet (a baby, or already shorn) still consumes the click, as the
  removed vanilla code did, so the cooldown starts as on Fabric.
- **The turtle helmet.** NeoForge rewrote the helmet's check in `Player.tick`
  from "eyes not in water" to "eyes not in a fluid the player can drown in".
  A swimming morph can breathe underwater, so underwater it counted as "not
  drowning" and got Water Breathing the whole time. `PlayerTurtleHelmetMixin`
  (`neoforge/src/main/java/com/deds/morph/neoforge/mixin/`) also counts eyes
  in water for a player with the swimming ability, which restores vanilla's
  answer for them; everyone else gets NeoForge's check unchanged.
- **How a mob was spawned.** NeoForge's mobs save `neoforge:spawn_type`
  (natural, spawn egg, command, and so on). Left in, three zombies spawned
  three ways would be three different morphs, so it is in
  `Morph.TRANSIENT_KEYS`. Fabric never writes it.

## What Ded's API does not do on NeoForge yet

Morph uses none of these. A future Ded's mod that needs one on NeoForge needs
a design first.

- `ModContext.blocks()`, `items()`, `effects()`, `tabs()`,
  `blockEntities()` and `fluids()` throw `UnsupportedOperationException`
  (`Ded's API <version> does not support ... on NeoForge yet`). NeoForge only
  takes registry entries inside `RegisterEvent`, and fluids use NeoForge's
  own transfer API and units.
- `BlockTints` and `BlockFaceSampler` throw the same kind of error from every
  method. `BlockModelWrappers` registrations are ignored, with one warning in
  the log at client setup.
- `PlayerEvents.ATTACK_BLOCK` and `USE_BLOCK` are not bridged: their
  listeners never fire on NeoForge.

## Data does not carry between loaders

Both loaders save the same two entries with the same fields, but in a
different place in the player's save: Fabric API under `fabric:attachments`,
NeoForge under `neoforge:attachments`. Neither loader reads the other's, so a
world moved from one loader to the other loses every player's morph
collection and worn morph. This is the other difference players can notice.
The settings file, `config/deds_morph/morph.json`, is the same on both.

## The tests

Both loaders run the same shared server tests (`common/src/gametest/`) and
each has a client test.

| Suite | Command | Part of `build` |
| --- | --- | --- |
| Fabric server tests | `./gradlew :fabric:runGameTest` | yes |
| NeoForge server tests | `./gradlew :neoforge:runGameTestServer` | yes |
| Fabric client test | `./gradlew :fabric:runClientGameTest` | no, it opens a game window |
| NeoForge client test | `./gradlew :neoforge:runClientGameTest` | no, it opens a game window |

Add `-Pmc=26.3` for Minecraft 26.3. `./gradlew build` runs both server suites.
The NeoForge build tools need a JDK 21 as well as the JDK 25 the mod uses;
Gradle downloads it if it is missing.

**How the NeoForge test mod runs the shared tests.** NeoForge has no
annotation-driven gametest registration, so the NeoForge test mod
(`neoforge/src/gametest/`) brings its own:

- small stand-ins, written for this project, for the two Fabric API types
  the shared tests use (`@GameTest` and `CustomTestMethodInvoker`, under
  `neoforge/src/gametest/java/net/fabricmc/fabric/api/gametest/v1/`, with a
  26.3 twin of `@GameTest` that adds `dimension`, as Fabric API's 26.3 build
  does);
- a registrar, `MorphNeoForgeGameTests`, that registers the classes listed
  in `common/src/gametest/resources/deds_morph_test/gametest-classes.txt`
  (the build keeps that list equal to the Fabric test mod's) under the same
  test names and with the same defaults as Fabric's locator. Unlike Fabric's,
  it throws on an empty list and on a listed class with no test in it, and
  the run only selects `deds_morph_test:*` tests, so a registration that
  found nothing fails the build;
- Fabric's empty 8x8x8 test structure, as
  `data/fabric-gametest-api-v1/structure/empty.nbt`, generated by
  `neoforge/tools/gen_empty_structure.py` and checked by
  `NeoForgeHarnessGameTests`, the one NeoForge-only test.

The one shared test that looks at loader internals (who may receive the
morph list) goes through `SyncProbe`, which each loader's test mod
implements.

**The two harnesses are not identical.** Tests must not depend on either set
of conditions:

| | Fabric | NeoForge |
| --- | --- | --- |
| Game rules | vanilla defaults: mobs spawn, weather changes | `spawn_mobs` and `advance_weather` turned off |
| World | kept between runs (`fabric/build/run/gameTest/`, or `gameTest-mc<version>/` for a non-canonical version) | deleted and made fresh on every run |
| `isDedicatedServer()` | true (Fabric API forces it) | false |
| Extra tests in the total | vanilla's optional `minecraft:always_pass` | `NeoForgeHarnessGameTests` |

The order of test batches is not fixed on either loader. When comparing the
two loaders' results, compare the `deds_morph_test` tests, not the totals.

**The NeoForge client test.** NeoForge has no client gametest API, so the
test mod carries a small driver (`MorphNeoForgeClientTests` and
`ClientTestDriver` under `neoforge/src/gametest/java/.../neoforge/client/`),
active only when the `clientGameTest` run sets `-Dmorph.clientTest=true`. It
creates a flat creative world as Fabric's harness does, runs its scenarios
(key bindings, morphing, the selector and real mouse input, the favourites
wheel, the acquisition effect, the first-person hand, a baby morph, and the
owner's list surviving death, the nether and a rejoin), then exits with 0
only if every check passed. Its game directory,
`neoforge/build/run/clientGameTest/`, is deleted before each run; it leaves
`morph-client-test-results.txt` and a `screenshots/` folder there. The mixin
audit in its first scenario only writes to the log, so its verdict is the log
between the lines `[deds_morph_test] MIXIN AUDIT BEGIN` and
`[deds_morph_test] MIXIN AUDIT END`: check that part for mixin errors.

On both loaders the client test's hard checks cover state and hooks. A
picture can still be wrong while every check passes, so open the
screenshots.
