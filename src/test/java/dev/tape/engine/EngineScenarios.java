package dev.tape.engine;

import dev.tape.application.*;
import dev.tape.domain.*;
import dev.tape.support.MemoryJournal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static dev.tape.domain.Side.*;
import static dev.tape.domain.Status.*;
import static dev.tape.domain.ExchangeException.Code.*;

/** Shared real scenarios: run via JUnit or via the dependency-free offline runner. */
public final class EngineScenarios {
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static UUID id(long number) { return new UUID(0, number); }
    private static CommandResult add(MatchingEngine engine, long id, Side side, int price, int quantity) {
        return engine.apply(new Command(engine.metrics().lastCommandSequence() + 1, Command.Kind.SUBMIT,
                id(id), side, price, quantity, TIME));
    }
    private static void cancel(MatchingEngine engine, long id) {
        engine.apply(new Command(engine.metrics().lastCommandSequence() + 1, Command.Kind.CANCEL,
                id(id), null, null, null, TIME));
    }
    private static ExchangeService service(MemoryJournal journal) {
        AtomicLong ids = new AtomicLong(100);
        return new ExchangeService(journal, () -> id(ids.incrementAndGet()), Clock.fixed(TIME, ZoneOffset.UTC));
    }
    private static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static void yes(boolean condition) { if (!condition) throw new AssertionError("Condition was false"); }
    private static void error(ExchangeException.Code code, Runnable action) {
        try { action.run(); } catch (ExchangeException failure) { eq(failure.code(), code); return; }
        throw new AssertionError("Expected " + code);
    }
    private static void corrupt(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected corrupt replay to fail");
    }

    public static void restingAndNoCross() {
        var e = new MatchingEngine();
        eq(add(e, 1, BUY, 100, 7).order().status(), OPEN);
        eq(add(e, 2, SELL, 102, 3).order().status(), OPEN);
        eq(e.book(10).bids().get(0).quantity(), 7L);
        eq(e.book(10).asks().get(0).quantity(), 3L);
        eq(e.book(10).spreadTicks(), 2);
        eq(e.metrics().executedTrades(), 0L);
        e.assertInvariants();
    }
    public static void makerPricingBothDirections() {
        var e = new MatchingEngine();
        add(e, 1, SELL, 100, 4);
        eq(add(e, 2, BUY, 110, 4).executions().get(0).priceTicks(), 100);
        add(e, 3, BUY, 99, 4);
        eq(add(e, 4, SELL, 90, 4).executions().get(0).priceTicks(), 99);
        e.assertInvariants();
    }
    public static void bestPriceBeforeEarlierWorsePrice() {
        var e = new MatchingEngine();
        add(e, 1, SELL, 110, 5);
        add(e, 2, SELL, 100, 5);
        eq(add(e, 3, BUY, 110, 2).executions().get(0).makerOrderId(), id(2));
        var b = new MatchingEngine();
        add(b, 1, BUY, 90, 5);
        add(b, 2, BUY, 99, 5);
        eq(add(b, 3, SELL, 90, 2).executions().get(0).makerOrderId(), id(2));
    }
    public static void fifoRetainedAfterPartialFill() {
        var e = new MatchingEngine();
        add(e, 1, SELL, 100, 5);
        add(e, 2, SELL, 100, 5);
        add(e, 3, BUY, 100, 2);
        var trades = add(e, 4, BUY, 100, 5).executions();
        eq(trades.stream().map(Trade::makerOrderId).toList(), List.of(id(1), id(2)));
        eq(trades.stream().map(Trade::quantity).toList(), List.of(3, 2));
        e.assertInvariants();
    }
    public static void completeFillNeverRests() {
        var e = new MatchingEngine();
        add(e, 1, BUY, 100, 4);
        eq(add(e, 2, SELL, 100, 4).order().status(), FILLED);
        eq(e.order(id(1)).remainingQuantity(), 0);
        eq(e.metrics().restingOrders(), 0);
        eq(e.book(10).spreadTicks(), null);
    }
    public static void partialMakerAndTaker() {
        var e = new MatchingEngine();
        add(e, 1, SELL, 100, 5);
        add(e, 2, BUY, 100, 2);
        eq(e.order(id(1)).status(), PARTIALLY_FILLED);
        eq(e.order(id(1)).cumulativeFilledQuantity(), 2);
        var result = add(e, 3, BUY, 101, 10);
        eq(result.order().status(), PARTIALLY_FILLED);
        eq(result.order().remainingQuantity(), 7);
        eq(e.book(10).bids().get(0).priceTicks(), 101);
        e.assertInvariants();
    }
    public static void multiLevelSweep() {
        var e = new MatchingEngine();
        add(e, 1, SELL, 100, 2);
        add(e, 2, SELL, 101, 3);
        add(e, 3, SELL, 102, 8);
        var result = add(e, 4, BUY, 101, 8);
        eq(result.executions().stream().map(Trade::priceTicks).toList(), List.of(100, 101));
        eq(result.order().remainingQuantity(), 3);
        eq(e.book(10).asks().get(0).priceTicks(), 102);
        e.assertInvariants();
    }
    public static void sellSweepDescendingBids() {
        var e = new MatchingEngine();
        add(e, 1, BUY, 98, 3);
        add(e, 2, BUY, 100, 2);
        add(e, 3, BUY, 99, 2);
        eq(add(e, 4, SELL, 98, 6).executions().stream().map(Trade::priceTicks).toList(), List.of(100, 99, 98));
        eq(e.book(10).bids().get(0).quantity(), 1L);
        e.assertInvariants();
    }
    public static void cancelPartialAndRemoveLevel() {
        var e = new MatchingEngine();
        add(e, 1, SELL, 100, 5);
        add(e, 2, BUY, 100, 2);
        cancel(e, 1);
        eq(e.order(id(1)).status(), CANCELLED);
        eq(e.order(id(1)).remainingQuantity(), 3);
        eq(e.order(id(1)).cumulativeFilledQuantity(), 2);
        eq(e.book(10).asks(), List.of());
        e.assertInvariants();
    }
    public static void cancelMiddlePreservesFifo() {
        var e = new MatchingEngine();
        for (int i = 1; i <= 3; i++) add(e, i, SELL, 100, 2);
        cancel(e, 2);
        eq(add(e, 4, BUY, 100, 4).executions().stream().map(Trade::makerOrderId).toList(), List.of(id(1), id(3)));
    }
    public static void cancelMissingFilledAndCancelled() {
        var e = new MatchingEngine();
        error(NOT_FOUND, () -> cancel(e, 8));
        add(e, 1, SELL, 100, 1);
        add(e, 2, BUY, 100, 1);
        error(CONFLICT, () -> cancel(e, 1));
        add(e, 3, BUY, 90, 1);
        cancel(e, 3);
        error(CONFLICT, () -> cancel(e, 3));
        eq(e.metrics().lastCommandSequence(), 4L);
    }
    public static void invalidInputsAndBounds() {
        var e = new MatchingEngine();
        for (int bad : new int[]{0, -1, Integer.MAX_VALUE}) {
            error(INVALID_INPUT, () -> add(e, 1, BUY, bad, 1));
            error(INVALID_INPUT, () -> add(e, 1, BUY, 1, bad));
        }
        error(INVALID_INPUT, () -> add(e, 1, null, 1, 1));
        error(INVALID_INPUT, () -> Command.validate(BUY, null, 1));
        error(INVALID_INPUT, () -> Command.validate(BUY, 1, null));
        error(INVALID_INPUT, () -> e.book(0));
        error(INVALID_INPUT, () -> e.book(101));
        eq(e.metrics().acceptedOrders(), 0L);
    }
    public static void duplicateIdsEvenAfterFill() {
        var e = new MatchingEngine();
        add(e, 1, BUY, 100, 1);
        error(CONFLICT, () -> add(e, 1, BUY, 100, 1));
        add(e, 2, SELL, 100, 1);
        error(CONFLICT, () -> add(e, 1, SELL, 100, 1));
        eq(e.metrics().acceptedOrders(), 2L);
    }
    public static void rejectSequenceGapsAndReordering() {
        var e = new MatchingEngine();
        error(CONFLICT, () -> e.apply(new Command(2, Command.Kind.SUBMIT, id(1), BUY, 100, 1, TIME)));
        add(e, 1, BUY, 100, 1);
        error(CONFLICT, () -> e.apply(new Command(1, Command.Kind.SUBMIT, id(2), BUY, 100, 1, TIME)));
    }
    public static void copyIsIsolatedAndSnapshotsImmutable() {
        var e = new MatchingEngine();
        add(e, 1, BUY, 100, 4);
        Order snapshot = e.order(id(1));
        var copy = e.copy();
        add(copy, 2, SELL, 100, 4);
        eq(e.order(id(1)), snapshot);
        eq(e.metrics().restingOrders(), 1);
        eq(copy.metrics().restingOrders(), 0);
        try { e.book(10).bids().clear(); throw new AssertionError("Mutable snapshot"); }
        catch (UnsupportedOperationException expected) { /* expected */ }
    }
    public static void stableReplayAndTradeHistory() {
        var journal = new MemoryJournal();
        var service = service(journal);
        var a = service.submit(SELL, 100, 4);
        service.submit(SELL, 101, 6);
        service.submit(BUY, 101, 7);
        error(CONFLICT, () -> service.cancel(a.order().id()));
        var restarted = service(journal);
        eq(restarted.trades(200), service.trades(200));
        eq(restarted.book(100), service.book(100));
    }
    public static void restartRestoresBookStatusesTrades() {
        var journal = new MemoryJournal();
        var first = service(journal);
        first.submit(SELL, 100, 4);
        var b = first.submit(SELL, 101, 6);
        first.submit(BUY, 101, 7);
        first.cancel(b.order().id());
        var open = first.submit(BUY, 98, 9);
        var restarted = service(journal);
        eq(restarted.book(100), first.book(100));
        eq(restarted.trades(200), first.trades(200));
        eq(restarted.metrics(), first.metrics());
        eq(restarted.order(open.order().id()), first.order(open.order().id()));
        eq(restarted.order(b.order().id()).status(), CANCELLED);
    }
    public static void corruptReplayFailsLoudly() {
        var journal = new MemoryJournal();
        var service = service(journal);
        service.submit(SELL, 100, 2);
        service.submit(BUY, 100, 2);
        var last = journal.entries.get(1);
        var t = last.executions().get(0);
        journal.entries.set(1, new Journal.Entry(last.command(), List.of(new Trade(t.id(), t.commandSequence(),
                t.executionIndex(), t.makerOrderId(), t.takerOrderId(), 101, t.quantity(), t.timestamp()))));
        corrupt(() -> service(journal));
        var gap = new MemoryJournal();
        gap.entries.add(last);
        corrupt(() -> service(gap));
    }
    public static void stateIsPublishedOnlyAfterCommit() {
        var journal = new MemoryJournal();
        var service = service(journal);
        var maker = service.submit(SELL, 100, 2);
        journal.beforeCommit = () -> {
            eq(service.order(maker.order().id()).status(), OPEN);
            eq(service.order(maker.order().id()).remainingQuantity(), 2);
            eq(service.metrics().acceptedOrders(), 1L);
            eq(service.trades(10).size(), 0);
        };
        service.submit(BUY, 100, 2);
        eq(service.order(maker.order().id()).status(), FILLED);
        eq(service.metrics().acceptedOrders(), 2L);
        eq(service.trades(10).size(), 1);
    }
    public static void rollbackAndFence() {
        var journal = new MemoryJournal();
        var service = service(journal);
        var maker = service.submit(SELL, 100, 2);
        journal.failBeforeCommit = true;
        error(UNAVAILABLE, () -> service.submit(BUY, 100, 2));
        eq(journal.entries.size(), 1);
        yes(!service.available());
        error(UNAVAILABLE, () -> service.book(10));
        error(UNAVAILABLE, () -> service.cancel(maker.order().id()));
        journal.failBeforeCommit = false;
        var restored = service(journal);
        eq(restored.order(maker.order().id()).remainingQuantity(), 2);
        eq(restored.trades(10).size(), 0);
    }
    public static void ambiguousCommitRecoveredByReplay() {
        var journal = new MemoryJournal();
        var service = service(journal);
        service.submit(SELL, 100, 2);
        journal.failAfterCommit = true;
        error(UNAVAILABLE, () -> service.submit(BUY, 100, 2));
        yes(!service.available());
        journal.failAfterCommit = false;
        var restored = service(journal);
        eq(restored.metrics().restingOrders(), 0);
        eq(restored.trades(10).size(), 1);
    }
    public static void duplicateGeneratorIsRejectedBeforePersistence() {
        var journal = new MemoryJournal();
        var service = new ExchangeService(journal, () -> id(1), Clock.fixed(TIME, ZoneOffset.UTC));
        service.submit(BUY, 100, 2);
        error(CONFLICT, () -> service.submit(BUY, 100, 2));
        eq(journal.entries.size(), 1);
        yes(service.available());
    }
    public static void concurrentSubmissionsAreSerialized() throws Exception {
        var journal = new MemoryJournal();
        var service = service(journal);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<CommandResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 160; i++) tasks.add(() -> service.submit(SELL, 100, 1));
            for (Future<CommandResult> future : pool.invokeAll(tasks)) eq(future.get().order().status(), OPEN);
            var fill = service.submit(BUY, 100, 160);
            eq(fill.executions().stream().map(Trade::makerOrderId).toList(),
                    journal.readAll().stream().limit(160).map(entry -> entry.command().orderId()).toList());
            for (int i = 0; i < journal.entries.size(); i++) eq(journal.entries.get(i).command().sequence(), (long) i + 1);
            var replay = service(journal);
            eq(replay.book(100), service.book(100));
            eq(replay.trades(200), service.trades(200));
            eq(service.metrics().executedTrades(), 160L);
        } finally { pool.shutdownNow(); }
    }
    public static void seededConservationAndAggregatedDepth() {
        var e = new MatchingEngine();
        var random = new Random(246813579L);
        Map<UUID, Integer> fills = new HashMap<>();
        for (int i = 1; i <= 5000; i++) {
            var result = add(e, i, random.nextBoolean() ? BUY : SELL, 90 + random.nextInt(21), 1 + random.nextInt(30));
            for (Trade t : result.executions()) {
                yes(t.quantity() > 0);
                fills.merge(t.makerOrderId(), t.quantity(), Math::addExact);
                fills.merge(t.takerOrderId(), t.quantity(), Math::addExact);
                eq(t.priceTicks(), e.order(t.makerOrderId()).priceTicks());
            }
            if (i % 7 == 0) {
                var resting = e.orders().stream().filter(Order::active).findFirst();
                resting.ifPresent(order -> e.apply(new Command(e.metrics().lastCommandSequence() + 1,
                        Command.Kind.CANCEL, order.id(), null, null, null, TIME)));
            }
            e.assertInvariants();
        }
        for (Order order : e.orders()) {
            eq(order.cumulativeFilledQuantity(), fills.getOrDefault(order.id(), 0));
            yes(order.cumulativeFilledQuantity() <= order.originalQuantity());
        }
        var book = e.book(100);
        for (Side side : Side.values()) {
            for (Book.Level level : side == BUY ? book.bids() : book.asks()) {
                long expected = e.orders().stream().filter(o -> o.active() && o.side() == side && o.priceTicks() == level.priceTicks())
                        .mapToLong(Order::remainingQuantity).sum();
                eq(level.quantity(), expected);
            }
        }
    }
    public static void wideAggregationAndBoundedViews() {
        var e = new MatchingEngine();
        for (int i = 1; i <= 2200; i++) add(e, i, BUY, 1, 1_000_000);
        eq(e.book(1).bids().get(0).quantity(), 2_200_000_000L);
        eq(e.book(1).bids().get(0).orderCount(), 2200);
        add(e, 3000, BUY, 2, 1);
        eq(e.book(1).bids().size(), 1);
        eq(e.book(1).bids().get(0).priceTicks(), 2);
        error(INVALID_INPUT, () -> service(new MemoryJournal()).trades(201));
        e.assertInvariants();
    }
    public static void main(String[] args) throws Exception {
        int count = 0;
        var methods = Arrays.stream(EngineScenarios.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()) && !m.getName().equals("main"))
                .sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList();
        for (var method : methods) {
            try { method.invoke(null); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError(method.getName(), failure.getCause()); }
            System.out.println("PASS " + method.getName());
            count++;
        }
        System.out.println("Verified " + count + " scenarios (plain Java runner; NOT a Maven/JUnit run).");
    }
}
