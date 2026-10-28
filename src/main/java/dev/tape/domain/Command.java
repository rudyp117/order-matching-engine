package dev.tape.domain;

import java.time.Instant;
import java.util.UUID;

public record Command(long sequence, Kind kind, UUID orderId, Side side,
                      Integer priceTicks, Integer quantity, Instant acceptedAt) {
    public enum Kind { SUBMIT, CANCEL }
    public static final int MAX_PRICE_TICKS = 100_000_000;
    public static final int MAX_QUANTITY = 1_000_000;
    public Command {
        if (sequence < 1 || kind == null || orderId == null || acceptedAt == null) {
            throw invalid("Sequence, kind, order ID and acceptedAt are required");
        }
        if (kind == Kind.SUBMIT) validate(side, priceTicks, quantity);
        else if (side != null || priceTicks != null || quantity != null) {
            throw invalid("Cancellation cannot contain order entry fields");
        }
    }
    public static void validate(Side side, Integer price, Integer quantity) {
        if (side == null || price == null || quantity == null
                || price < 1 || price > MAX_PRICE_TICKS || quantity < 1 || quantity > MAX_QUANTITY) {
            throw invalid("Side is required; priceTicks must be 1..100000000; quantity must be 1..1000000");
        }
    }
    private static ExchangeException invalid(String message) {
        return new ExchangeException(ExchangeException.Code.INVALID_INPUT, message);
    }
}
