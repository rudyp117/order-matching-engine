package dev.tape.support;

import dev.tape.application.Journal;
import dev.tape.domain.*;
import java.util.*;

/** Test fixture only. Production always uses PostgreSQL. */
public final class MemoryJournal implements Journal {
    public final List<Entry> entries = new ArrayList<>();
    public Runnable beforeCommit = () -> { };
    public boolean failBeforeCommit;
    public boolean failAfterCommit;
    @Override public synchronized List<Entry> readAll() { return List.copyOf(entries); }
    @Override public synchronized void append(Command command, List<Trade> executions) {
        beforeCommit.run();
        if (failBeforeCommit) throw new IllegalStateException("Injected rollback");
        entries.add(new Entry(command, executions));
        if (failAfterCommit) throw new IllegalStateException("Injected lost commit acknowledgement");
    }
    @Override public synchronized List<Trade> recentTrades(int limit) {
        List<Trade> trades = new ArrayList<>();
        entries.forEach(entry -> trades.addAll(entry.executions()));
        Collections.reverse(trades);
        return List.copyOf(trades.subList(0, Math.min(limit, trades.size())));
    }
}
