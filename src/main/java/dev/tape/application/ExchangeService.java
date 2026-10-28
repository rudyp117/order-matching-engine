package dev.tape.application;

import dev.tape.domain.*;
import dev.tape.engine.MatchingEngine;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import static dev.tape.domain.ExchangeException.Code.*;

/** One lock orders commands, commit, publication, and reads in this single process. */
public final class ExchangeService {
    private final ReentrantLock lock = new ReentrantLock();
    private final Journal journal;
    private final Supplier<UUID> orderIds;
    private final Clock clock;
    private MatchingEngine live;
    private boolean fenced;

    public ExchangeService(Journal journal, Supplier<UUID> orderIds, Clock clock) {
        this.journal = Objects.requireNonNull(journal);
        this.orderIds = Objects.requireNonNull(orderIds);
        this.clock = Objects.requireNonNull(clock);
        MatchingEngine restored = new MatchingEngine();
        try {
            for (Journal.Entry entry : journal.readAll()) {
                CommandResult replayed = restored.apply(entry.command());
                if (!replayed.executions().equals(entry.executions())) {
                    throw new IllegalStateException("Recorded executions differ at sequence " + entry.command().sequence());
                }
            }
            restored.assertInvariants();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Journal replay failed; application must not serve requests", failure);
        }
        live = restored;
    }
    public CommandResult submit(Side side, Integer priceTicks, Integer quantity) {
        Command.validate(side, priceTicks, quantity);
        lock.lock();
        try {
            ready();
            return execute(new Command(Math.addExact(live.metrics().lastCommandSequence(), 1), Command.Kind.SUBMIT,
                    orderIds.get(), side, priceTicks, quantity, clock.instant().truncatedTo(ChronoUnit.MICROS)));
        } finally { lock.unlock(); }
    }
    public CommandResult cancel(UUID id) {
        if (id == null) throw new ExchangeException(INVALID_INPUT, "Order ID is required");
        lock.lock();
        try {
            ready();
            return execute(new Command(Math.addExact(live.metrics().lastCommandSequence(), 1), Command.Kind.CANCEL,
                    id, null, null, null, clock.instant().truncatedTo(ChronoUnit.MICROS)));
        } finally { lock.unlock(); }
    }
    private CommandResult execute(Command command) {
        MatchingEngine candidate = live.copy();
        CommandResult result = candidate.apply(command);
        try {
            journal.append(command, result.executions()); // returns only AFTER commit
        } catch (RuntimeException failure) {
            fenced = true; // commit outcome can be ambiguous; never continue on stale state
            throw new ExchangeException(UNAVAILABLE, "Persistence failed; restart and replay before continuing");
        }
        live = candidate;
        return result;
    }
    public Order order(UUID id) { return read(() -> live.order(id)); }
    public Book book(int depth) { return read(() -> live.book(depth)); }
    public Metrics metrics() { return read(() -> live.metrics()); }
    public List<Trade> trades(int limit) {
        if (limit < 1 || limit > 200) throw new ExchangeException(INVALID_INPUT, "limit must be 1..200");
        return read(() -> journal.recentTrades(limit));
    }
    public boolean available() {
        lock.lock();
        try { return !fenced; } finally { lock.unlock(); }
    }
    private <T> T read(Supplier<T> action) {
        lock.lock();
        try { ready(); return action.get(); } finally { lock.unlock(); }
    }
    private void ready() {
        if (fenced) throw new ExchangeException(UNAVAILABLE, "Service fenced after persistence failure; restart required");
    }
}
