package dev.tape.domain;

import java.util.List;

public record Book(String symbol, List<Level> bids, List<Level> asks, Integer spreadTicks) {
    public record Level(int priceTicks, long quantity, int orderCount) { }
    public Book { bids = List.copyOf(bids); asks = List.copyOf(asks); }
}
