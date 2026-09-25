package com.deds.api.command;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Registers Brigadier commands for one mod (Ded's API v1.1). Call during
 * {@link com.deds.api.DedsMod#onInitialize}; the {@link Builder} runs every
 * time the server (re)builds its command tree.
 *
 * <p>Brigadier ({@code com.mojang.brigadier.*}) and {@code
 * net.minecraft.commands.*} are accepted vanilla surface (see
 * docs/ARCHITECTURE.md) — mod command code builds nodes with
 * {@link Commands#literal} / {@link Commands#argument} and gates them with
 * {@link Commands#hasPermission} directly, without touching
 * {@code net.fabricmc.*}.</p>
 */
public interface CommandRegistrar {

    /**
     * Builds one mod's command tree. Invoked on every command-tree (re)build
     * with the live dispatcher, the build context, and the selection
     * ({@code ALL}/{@code DEDICATED}/{@code INTEGRATED}).
     */
    @FunctionalInterface
    interface Builder {
        void build(CommandDispatcher<CommandSourceStack> dispatcher,
                   CommandBuildContext buildContext,
                   Commands.CommandSelection selection);
    }

    /** Registers a command-tree builder. */
    void register(Builder builder);
}
