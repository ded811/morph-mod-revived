package com.deds.api.fabric;

import com.deds.api.energy.EnergyPort;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import team.reborn.energy.api.EnergyStorage;

/**
 * Turns a Team Reborn transactional {@code EnergyStorage} back into the
 * simulate-then-commit shape a 1.6.4 port is written against (Ded's API
 * v2.4) — the energy twin of {@link FabricFluidPort}, one method shorter
 * because energy has no "what is stored" question to answer: there is only
 * one resource.
 *
 * <p>Every call opens its own outer transaction and either commits it or
 * lets {@code close()} abort it. The consequence — no cross-call atomicity,
 * and never call this from inside another transaction — is stated on
 * {@link EnergyPort}.</p>
 */
record FabricEnergyPort(EnergyStorage storage) implements EnergyPort {

    @Override
    public int insert(int amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = storage.insert(FabricEnergyBridge.toE(amount),
                    transaction);
            if (!simulate) {
                transaction.commit();
            }
            return (int) FabricEnergyBridge.toRf(moved);
        }
    }

    @Override
    public int extract(int amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = storage.extract(FabricEnergyBridge.toE(amount),
                    transaction);
            if (!simulate) {
                transaction.commit();
            }
            return (int) FabricEnergyBridge.toRf(moved);
        }
    }
}
