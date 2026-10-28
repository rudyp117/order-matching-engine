package dev.tape.persistence;

import dev.tape.ExchangeApplication;
import dev.tape.application.ExchangeService;
import dev.tape.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL + two complete Spring contexts. Requires a disposable, initially empty test DB. */
class PostgresRestartIT {
    private final String url = System.getenv().getOrDefault("IT_DB_URL", "jdbc:postgresql://localhost:15432/tape_it");
    private final String user = System.getenv().getOrDefault("IT_DB_USER", "tape_test");
    private final String password = System.getenv().getOrDefault("IT_DB_PASSWORD", "tape_test_only");
    private ConfigurableApplicationContext start() {
        return SpringApplication.run(ExchangeApplication.class, "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=" + url, "--spring.datasource.username=" + user,
                "--spring.datasource.password=" + password, "--spring.main.banner-mode=off");
    }
    @Test void transactionsFullApplicationRestartAndCorruptionDetection() throws Exception {
        Book before;
        List<Trade> history;
        Metrics metrics;
        UUID partial;
        UUID cancelled;
        try (var first = start()) {
            var service = first.getBean(ExchangeService.class);
            assertEquals(0L, service.metrics().acceptedOrders(), "Use a disposable EMPTY test database; this test never wipes data");
            service.submit(Side.SELL, 100, 4);
            partial = service.submit(Side.SELL, 101, 8).order().id();
            service.submit(Side.BUY, 101, 7);
            cancelled = service.submit(Side.BUY, 90, 3).order().id();
            service.cancel(cancelled);
            service.submit(Side.BUY, 99, 6);
            before = service.book(100);
            history = service.trades(200);
            metrics = service.metrics();
            assertEquals(2, history.size());
            assertEquals(5, service.order(partial).remainingQuantity());
            int port = Integer.parseInt(first.getEnvironment().getProperty("local.server.port"));
            var client = HttpClient.newHttpClient();
            for (String path : List.of("/", "/api/book", "/api/trades", "/actuator/health")) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                assertFalse(response.body().isBlank());
            }
        }
        // New application context creates a new datasource, journal and core; no in-memory fixture is reused.
        try (var restarted = start()) {
            var service = restarted.getBean(ExchangeService.class);
            assertEquals(before, service.book(100));
            assertEquals(history, service.trades(200));
            assertEquals(metrics, service.metrics());
            assertEquals(Status.PARTIALLY_FILLED, service.order(partial).status());
            assertEquals(Status.CANCELLED, service.order(cancelled).status());

            var ds = new DriverManagerDataSource(url, user, password);
            var journal = new PostgresJournal(ds);
            long next = metrics.lastCommandSequence() + 1;
            UUID taker = UUID.randomUUID();
            Instant time = Instant.parse("2026-01-01T00:00:00Z");
            var command = new Command(next, Command.Kind.SUBMIT, taker, Side.BUY, 101, 1, time);
            var invalidTrade = new Trade(UUID.randomUUID(), next, 0, partial, taker, 101, 0, time);
            assertThrows(PostgresJournal.JournalFailure.class, () -> journal.append(command, List.of(invalidTrade)));
            assertEquals(metrics.lastCommandSequence(), journal.readAll().get(journal.readAll().size() - 1).command().sequence());
            assertEquals(history, journal.recentTrades(200), "Execution failure must roll back command too");
            try (Connection c = ds.getConnection(); Statement statement = c.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE accepted_commands SET quantity = quantity WHERE sequence = 1"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM executions"));
                assertThrows(SQLException.class, () -> statement.execute("TRUNCATE executions"));
            }
        }
        var journal = new PostgresJournal(new DriverManagerDataSource(url, user, password));
        // A SQL-valid but semantically invalid tail is rejected during replay. Test DB only.
        journal.append(new Command(metrics.lastCommandSequence() + 1, Command.Kind.CANCEL, UUID.randomUUID(),
                null, null, null, Instant.parse("2026-01-01T00:00:00Z")), List.of());
        assertThrows(IllegalStateException.class, () -> new ExchangeService(journal, UUID::randomUUID, Clock.systemUTC()));
    }
}
