package dev.tape.benchmark;

import dev.tape.domain.*;
import dev.tape.engine.MatchingEngine;
import java.time.Instant;
import java.util.UUID;

/** A real deterministic console session; no HTTP or database is claimed here. */
public final class SampleSession {
    public static void main(String[] args) {
        MatchingEngine e = new MatchingEngine();
        submit(e, 1, Side.SELL, 10000, 5);
        submit(e, 2, Side.SELL, 10000, 5);
        submit(e, 3, Side.BUY, 10000, 7);
        System.out.println("Second maker: " + e.order(new UUID(0, 2)).status() + ", remaining=" + e.order(new UUID(0, 2)).remainingQuantity());
        submit(e, 4, Side.SELL, 10001, 4);
        submit(e, 5, Side.BUY, 10001, 8);
        var cancellation = e.apply(new Command(6, Command.Kind.CANCEL, new UUID(0, 5), null, null, null, Instant.EPOCH));
        System.out.println("6 CANCEL #5 -> " + cancellation.order().status() + ", remaining=" + cancellation.order().remainingQuantity());
        e.assertInvariants();
        System.out.println(e.metrics());
        System.out.println(e.book(10));
    }
    private static void submit(MatchingEngine e, long id, Side side, int price, int quantity) {
        var result = e.apply(new Command(e.metrics().lastCommandSequence() + 1, Command.Kind.SUBMIT,
                new UUID(0, id), side, price, quantity, Instant.EPOCH));
        System.out.printf("%d %s %d @ %d -> %s, remaining=%d%n", result.commandSequence(), side, quantity, price,
                result.order().status(), result.order().remainingQuantity());
        for (Trade t : result.executions()) System.out.printf("  maker #%d: %d @ %d%n", t.makerOrderId().getLeastSignificantBits(), t.quantity(), t.priceTicks());
    }
}
