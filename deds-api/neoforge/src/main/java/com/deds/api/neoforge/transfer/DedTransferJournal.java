package com.deds.api.neoforge.transfer;

import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rollback and change notification for every tank and energy buffer Ded's
 * API exposes on NeoForge: ONE journal per thread with a last-in-first-out
 * undo log.
 *
 * <p><b>Why not one snapshot per adapter, as on Fabric.</b> Fabric closes a
 * transaction's participants in REVERSE order; NeoForge closes its journals in
 * FORWARD order. When one transaction writes the same tank through two
 * adapters (a cable reaching an energy cell from two faces, two lookups of one
 * tank column), per-adapter snapshots restore in the wrong order on NeoForge
 * and an aborted transfer leaves energy or fluid behind that never existed.
 * An undo log replayed newest-first restores the exact prior state no matter
 * how many adapters touched the same target, and does not depend on adapter
 * identity at all.</p>
 *
 * <p>Protocol: an adapter calls {@link #record} with an {@link Undo} BEFORE
 * it changes anything. The snapshot NeoForge keeps per transaction depth is
 * just the log length at that depth's first change: an abort undoes back to
 * it, a nested commit leaves the entries for the enclosing transaction, and
 * the root commit notifies every changed target once and clears the log.
 * Notification runs after NeoForge has closed the root transaction, so a
 * listener may open a new one.</p>
 *
 * <p>Transactions are per thread in NeoForge (each thread has its own
 * transaction manager), which is why the journal is too.</p>
 */
final class DedTransferJournal extends SnapshotJournal<Integer> {

    /** One reversible change. */
    interface Undo {
        /** The target to notify once after a committed change. */
        Object target();

        /** Puts the target back exactly as it was before the change. */
        void undo();

        /** Tells the target it changed (its {@code onChanged}). */
        void notifyChanged();
    }

    private static final ThreadLocal<DedTransferJournal> CURRENT =
            ThreadLocal.withInitial(DedTransferJournal::new);

    private final ArrayList<Undo> log = new ArrayList<>();

    private DedTransferJournal() {
    }

    /** Records {@code undo} in the current thread's journal. */
    static void record(TransactionContext transaction, Undo undo) {
        DedTransferJournal journal = CURRENT.get();
        try {
            journal.updateSnapshots(transaction);
        } catch (RuntimeException e) {
            // NeoForge stores the snapshot BEFORE it checks that the
            // transaction is open, so a caller that passes a closed one
            // leaves this journal holding a snapshot no transaction will
            // close, and every later transfer on this thread would stop
            // rolling back. Retire the instance (the transactions it is
            // registered with still close it normally) and let the caller
            // see the error, as it would on Fabric.
            CURRENT.set(new DedTransferJournal());
            throw e;
        }
        journal.log.add(undo);
    }

    @Override
    protected Integer createSnapshot() {
        return log.size();
    }

    /**
     * Undoes back to {@code mark}, newest first. Every undo runs even if one
     * throws (as Fabric closes every participant); the first failure is
     * rethrown at the end.
     */
    @Override
    protected void revertToSnapshot(Integer mark) {
        RuntimeException first = null;
        for (int i = log.size() - 1; i >= mark; i--) {
            try {
                log.remove(i).undo();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    @Override
    protected void onRootCommit(Integer originalMark) {
        List<Undo> done = new ArrayList<>(log);
        log.clear();
        // once per target, in the order they first changed, telling the LAST
        // adapter that wrote it (the most recent view of that target)
        Map<Object, Undo> last = new IdentityHashMap<>();
        List<Object> order = new ArrayList<>();
        for (Undo undo : done) {
            if (last.put(undo.target(), undo) == null) {
                order.add(undo.target());
            }
        }
        RuntimeException first = null;
        for (Object target : order) {
            try {
                last.get(target).notifyChanged();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
