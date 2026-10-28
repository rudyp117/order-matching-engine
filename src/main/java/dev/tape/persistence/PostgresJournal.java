package dev.tape.persistence;

import dev.tape.application.Journal;
import dev.tape.domain.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Parameterized JDBC, explicit commit boundary. Schema is owned by Flyway. */
public final class PostgresJournal implements Journal {
    private static final String COMMAND_COLUMNS = "sequence, kind, order_id, side, price_ticks, quantity, accepted_at";
    private static final String TRADE_COLUMNS = "id, command_sequence, execution_index, maker_order_id, taker_order_id, price_ticks, quantity, executed_at";
    private final DataSource dataSource;
    public PostgresJournal(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override public void append(Command command, List<Trade> trades) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO accepted_commands (" + COMMAND_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    statement.setLong(1, command.sequence());
                    statement.setString(2, command.kind().name());
                    statement.setObject(3, command.orderId());
                    statement.setString(4, command.side() == null ? null : command.side().name());
                    statement.setObject(5, command.priceTicks(), Types.INTEGER);
                    statement.setObject(6, command.quantity(), Types.INTEGER);
                    statement.setObject(7, command.acceptedAt().atOffset(ZoneOffset.UTC));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO executions (" + TRADE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (Trade trade : trades) {
                        statement.setObject(1, trade.id());
                        statement.setLong(2, trade.commandSequence());
                        statement.setInt(3, trade.executionIndex());
                        statement.setObject(4, trade.makerOrderId());
                        statement.setObject(5, trade.takerOrderId());
                        statement.setInt(6, trade.priceTicks());
                        statement.setInt(7, trade.quantity());
                        statement.setObject(8, trade.timestamp().atOffset(ZoneOffset.UTC));
                        statement.addBatch();
                    }
                    if (!trades.isEmpty()) statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw new JournalFailure("Could not commit accepted command", failure);
            }
        } catch (SQLException failure) { throw new JournalFailure("Database connection failed", failure); }
    }

    @Override public List<Entry> readAll() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                List<Command> commands = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("SELECT " + COMMAND_COLUMNS + " FROM accepted_commands ORDER BY sequence");
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) commands.add(readCommand(rows));
                }
                Map<Long, List<Trade>> trades = new HashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRADE_COLUMNS + " FROM executions ORDER BY command_sequence, execution_index");
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Trade trade = readTrade(rows);
                        trades.computeIfAbsent(trade.commandSequence(), ignored -> new ArrayList<>()).add(trade);
                    }
                }
                List<Entry> entries = new ArrayList<>();
                for (Command command : commands) {
                    List<Trade> executions = trades.remove(command.sequence());
                    entries.add(new Entry(command, executions == null ? List.of() : executions));
                }
                if (!trades.isEmpty()) throw new IllegalStateException("Executions without accepted commands");
                connection.commit();
                return List.copyOf(entries);
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw new JournalFailure("Cannot read consistent replay log", failure);
            }
        } catch (SQLException failure) { throw new JournalFailure("Database connection failed", failure); }
    }
    @Override public List<Trade> recentTrades(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be 1..200");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + TRADE_COLUMNS
                     + " FROM executions ORDER BY command_sequence DESC, execution_index DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Trade> result = new ArrayList<>();
                while (rows.next()) result.add(readTrade(rows));
                return List.copyOf(result);
            }
        } catch (SQLException failure) { throw new JournalFailure("Cannot read trade history", failure); }
    }
    private static Command readCommand(ResultSet rows) throws SQLException {
        String side = rows.getString("side");
        return new Command(rows.getLong("sequence"), Command.Kind.valueOf(rows.getString("kind")),
                rows.getObject("order_id", UUID.class), side == null ? null : Side.valueOf(side),
                rows.getObject("price_ticks", Integer.class), rows.getObject("quantity", Integer.class),
                rows.getObject("accepted_at", OffsetDateTime.class).toInstant());
    }
    private static Trade readTrade(ResultSet rows) throws SQLException {
        return new Trade(rows.getObject("id", UUID.class), rows.getLong("command_sequence"), rows.getInt("execution_index"),
                rows.getObject("maker_order_id", UUID.class), rows.getObject("taker_order_id", UUID.class),
                rows.getInt("price_ticks"), rows.getInt("quantity"), rows.getObject("executed_at", OffsetDateTime.class).toInstant());
    }
    public static final class JournalFailure extends RuntimeException {
        public JournalFailure(String message, Throwable cause) { super(message, cause); }
    }
}
