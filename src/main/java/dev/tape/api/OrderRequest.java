package dev.tape.api;

import dev.tape.domain.Side;
import jakarta.validation.constraints.*;

public record OrderRequest(@NotNull Side side,
                           @NotNull @Min(1) @Max(100000000) Integer priceTicks,
                           @NotNull @Min(1) @Max(1000000) Integer quantity) { }
