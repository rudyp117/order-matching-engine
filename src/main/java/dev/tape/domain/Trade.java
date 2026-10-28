package dev.tape.domain;

import java.time.Instant;
import java.util.UUID;

public record Trade(UUID id, long commandSequence, int executionIndex, UUID makerOrderId,
                    UUID takerOrderId, int priceTicks, int quantity, Instant timestamp) { }
