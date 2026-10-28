package dev.tape.api;

import dev.tape.application.ExchangeService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("exchange")
public final class ExchangeHealth implements HealthIndicator {
    private final ExchangeService service;
    public ExchangeHealth(ExchangeService service) { this.service = service; }
    @Override public Health health() { return service.available() ? Health.up().build() : Health.down().build(); }
}
