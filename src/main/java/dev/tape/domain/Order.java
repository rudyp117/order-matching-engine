package dev.tape.domain;

import java.util.UUID;

/** Immutable value. Cancelled orders retain their unexecuted remaining quantity. */
public record Order(UUID id, Side side, int priceTicks, int originalQuantity,
                    int remainingQuantity, int cumulativeFilledQuantity,
                    Status status, long sequence) {
    public boolean active() { return status == Status.OPEN || status == Status.PARTIALLY_FILLED; }
    public Order fill(int quantity) {
        if (!active() || quantity < 1 || quantity > remainingQuantity) {
            throw new IllegalStateException("Invalid fill");
        }
        int remaining = Math.subtractExact(remainingQuantity, quantity);
        return new Order(id, side, priceTicks, originalQuantity, remaining,
                Math.addExact(cumulativeFilledQuantity, quantity),
                remaining == 0 ? Status.FILLED : Status.PARTIALLY_FILLED, sequence);
    }
    public Order cancel() {
        if (!active()) throw new IllegalStateException("Order is not active");
        return new Order(id, side, priceTicks, originalQuantity, remainingQuantity,
                cumulativeFilledQuantity, Status.CANCELLED, sequence);
    }
}
