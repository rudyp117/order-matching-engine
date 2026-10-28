package dev.tape.domain;

public record Metrics(int restingOrders, long acceptedOrders, long executedTrades,
                      int bidLevels, int askLevels, long lastCommandSequence) { }
