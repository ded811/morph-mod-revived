package com.deds.morph;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /morph} command tree (derived from the original
 * {@code CommandMorph}; docs/specs/morph/SPEC.md §2). Thin Brigadier wrappers:
 * each handler only parses args, calls a {@link Morph} server seam, and prints
 * the original's chat text. Registered from {@link Morph#onInitialize} via
 * {@code ctx.commands()}.
 *
 * <p>Permission: {@link Commands#LEVEL_GAMEMASTERS} (level 2, the modern
 * cheat-command convention; the original's {@code CommandBase} default was
 * level 4 — see SPEC deviations). Mod code uses {@code net.minecraft.commands.*}
 * and {@code com.mojang.brigadier.*} directly (accepted vanilla surface) and
 * never touches {@code net.fabricmc.*}.</p>
 */
final class MorphCommand {

    private MorphCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("morph")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> {
                    help(c);
                    return 1;
                })
                .then(Commands.literal("help").executes(c -> {
                    help(c);
                    return 1;
                }))
                .then(Commands.literal("demorph")
                        .executes(c -> demorph(c,
                                c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player",
                                        EntityArgument.player())
                                .executes(c -> demorph(c,
                                        EntityArgument.getPlayer(c, "player")))
                                // [force] parsed for parity; offline path
                                // deferred — only the online path acts.
                                .then(Commands.argument("force",
                                                BoolArgumentType.bool())
                                        .executes(c -> demorph(c,
                                                EntityArgument.getPlayer(c,
                                                        "player"))))))
                .then(Commands.literal("clear")
                        .executes(c -> clear(c,
                                c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player",
                                        EntityArgument.player())
                                .executes(c -> clear(c,
                                        EntityArgument.getPlayer(c,
                                                "player")))))
                .then(Commands.literal("morphtarget")
                        .executes(c -> morphtarget(c,
                                c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player",
                                        EntityArgument.player())
                                .executes(c -> morphtarget(c,
                                        EntityArgument.getPlayer(c,
                                                "player")))))
                .then(Commands.literal("whitelist")
                        .then(Commands.argument("player",
                                        StringArgumentType.word())
                                .executes(c -> whitelist(c,
                                        StringArgumentType.getString(c,
                                                "player"), true))))
                .then(Commands.literal("unwhitelist")
                        .then(Commands.argument("player",
                                        StringArgumentType.word())
                                .executes(c -> whitelist(c,
                                        StringArgumentType.getString(c,
                                                "player"), false)))));
    }

    private static int demorph(CommandContext<CommandSourceStack> c,
            ServerPlayer player) {
        if (Morph.demorph(player)) {
            c.getSource().sendSuccess(() -> Component.literal(
                    "Forcing " + player.getScoreboardName() + " to demorph"),
                    true);
        } else {
            c.getSource().sendFailure(Component.literal(
                    player.getScoreboardName() + " is not in morph!"));
        }
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> c,
            ServerPlayer player) {
        if (Morph.clear(player)) {
            c.getSource().sendSuccess(() -> Component.literal(
                    "Clearing " + player.getScoreboardName() + "'s morphs"),
                    true);
        } else {
            c.getSource().sendFailure(Component.literal(
                    player.getScoreboardName() + " is currently morphing"));
        }
        return 1;
    }

    private static int morphtarget(CommandContext<CommandSourceStack> c,
            ServerPlayer player) {
        if (Morph.morphTarget(player)) {
            c.getSource().sendSuccess(() -> Component.literal("Forcing "
                    + player.getScoreboardName()
                    + " to morph into its target."), true);
        } else {
            c.getSource().sendFailure(Component.literal(
                    player.getScoreboardName()
                    + " is not looking at a morphable mob."));
        }
        return 1;
    }

    private static int whitelist(CommandContext<CommandSourceStack> c,
            String name, boolean add) {
        ServerPlayer sender = c.getSource().getPlayer();
        if (add) {
            if (Morph.whitelistAdd(sender, name)) {
                c.getSource().sendSuccess(
                        () -> Component.literal("Whitelisting " + name), true);
            } else {
                c.getSource().sendFailure(
                        Component.literal("Player already whitelisted"));
            }
        } else {
            if (Morph.whitelistRemove(sender, name)) {
                c.getSource().sendSuccess(
                        () -> Component.literal("Unwhitelisting " + name), true);
            } else {
                c.getSource().sendFailure(
                        Component.literal("Player is not on whitelist"));
            }
        }
        return 1;
    }

    /** The original's five gray usage lines (exact text UNVERIFIED). */
    private static void help(CommandContext<CommandSourceStack> c) {
        line(c, "/morph help");
        line(c, "/morph demorph [player] [force]");
        line(c, "/morph clear [player]");
        line(c, "/morph morphtarget [player]");
        line(c, "/morph whitelist|unwhitelist <player>");
    }

    private static void line(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendSystemMessage(
                Component.literal(text).withStyle(ChatFormatting.GRAY));
    }
}
