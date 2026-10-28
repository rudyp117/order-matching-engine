package dev.tape.engine;

import dev.tape.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.tape.domain.ExchangeException.Code.*;

/** Deterministic, deliberately unsynchronized. Its owner serializes all access. */
public final class MatchingEngine {
    private final NavigableMap<Integer, LinkedHashMap<UUID, Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Integer, LinkedHashMap<UUID, Order>> asks = new TreeMap<>();
    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, Order> active = new HashMap<>();
    private long sequence;
    private long acceptedOrders;
    private long executedTrades;

    public MatchingEngine copy() {
        MatchingEngine copy = new MatchingEngine();
        bids.forEach((price, level) -> copy.bids.put(price, new LinkedHashMap<>(level)));
        asks.forEach((price, level) -> copy.asks.put(price, new LinkedHashMap<>(level)));
        copy.orders.putAll(orders);
        copy.active.putAll(active);
        copy.sequence = sequence;
        copy.acceptedOrders = acceptedOrders;
        copy.executedTrades = executedTrades;
        return copy;
    }

    public CommandResult apply(Command command) {
        Objects.requireNonNull(command, "command");
        if (command.sequence() != Math.addExact(sequence, 1)) {
            throw new ExchangeException(CONFLICT, "Commands must have contiguous server sequences");
        }
        CommandResult result = command.kind() == Command.Kind.SUBMIT ? submit(command) : cancel(command);
        sequence = command.sequence();
        return result;
    }

    private CommandResult submit(Command command) {
        if (orders.containsKey(command.orderId())) throw new ExchangeException(CONFLICT, "Duplicate order ID");
        Order taker = new Order(command.orderId(), command.side(), command.priceTicks(), command.quantity(),
                command.quantity(), 0, Status.OPEN, command.sequence());
        List<Trade> trades = new ArrayList<>();
        NavigableMap<Integer, LinkedHashMap<UUID, Order>> opposite = levels(taker.side() == Side.BUY ? Side.SELL : Side.BUY);
        while (taker.remainingQuantity() > 0 && !opposite.isEmpty()) {
            var best = opposite.firstEntry();
            if (taker.side() == Side.BUY ? taker.priceTicks() < best.getKey() : taker.priceTicks() > best.getKey()) break;
            Order maker = best.getValue().values().iterator().next();
            int quantity = Math.min(taker.remainingQuantity(), maker.remainingQuantity());
            taker = taker.fill(quantity);
            Order filledMaker = maker.fill(quantity);
            orders.put(maker.id(), filledMaker);
            if (filledMaker.remainingQuantity() == 0) {
                active.remove(maker.id());
                best.getValue().remove(maker.id());
                if (best.getValue().isEmpty()) opposite.remove(best.getKey());
            } else {
                active.put(maker.id(), filledMaker);
                best.getValue().put(maker.id(), filledMaker); // replacement preserves FIFO position
            }
            int executionIndex = trades.size();
            UUID tradeId = UUID.nameUUIDFromBytes(("TAPE:" + command.sequence() + ":" + executionIndex)
                    .getBytes(StandardCharsets.UTF_8));
            trades.add(new Trade(tradeId, command.sequence(), executionIndex, maker.id(), taker.id(),
                    maker.priceTicks(), quantity, command.acceptedAt()));
        }
        orders.put(taker.id(), taker);
        if (taker.remainingQuantity() > 0) {
            active.put(taker.id(), taker);
            levels(taker.side()).computeIfAbsent(taker.priceTicks(), ignored -> new LinkedHashMap<>()).put(taker.id(), taker);
        }
        acceptedOrders = Math.addExact(acceptedOrders, 1);
        executedTrades = Math.addExact(executedTrades, trades.size());
        return new CommandResult(taker, trades, command.sequence());
    }

    private CommandResult cancel(Command command) {
        Order order = order(command.orderId());
        if (!order.active()) throw new ExchangeException(CONFLICT, "Only resting orders can be cancelled");
        var book = levels(order.side());
        var level = book.get(order.priceTicks());
        level.remove(order.id());
        if (level.isEmpty()) book.remove(order.priceTicks());
        active.remove(order.id());
        Order cancelled = order.cancel();
        orders.put(order.id(), cancelled);
        return new CommandResult(cancelled, List.of(), command.sequence());
    }

    public Order order(UUID id) {
        Order order = orders.get(id);
        if (order == null) throw new ExchangeException(NOT_FOUND, "Order not found");
        return order;
    }
    public List<Order> orders() { return List.copyOf(orders.values()); }
    public Book book(int depth) {
        if (depth < 1 || depth > 100) throw new ExchangeException(INVALID_INPUT, "depth must be 1..100");
        Integer spread = bids.isEmpty() || asks.isEmpty() ? null : Math.subtractExact(asks.firstKey(), bids.firstKey());
        return new Book("TAPE", aggregate(bids, depth), aggregate(asks, depth), spread);
    }
    private List<Book.Level> aggregate(NavigableMap<Integer, LinkedHashMap<UUID, Order>> levels, int depth) {
        List<Book.Level> result = new ArrayList<>();
        for (var entry : levels.entrySet()) {
            long quantity = 0;
            for (Order order : entry.getValue().values()) quantity = Math.addExact(quantity, order.remainingQuantity());
            result.add(new Book.Level(entry.getKey(), quantity, entry.getValue().size()));
            if (result.size() == depth) break;
        }
        return result;
    }
    public Metrics metrics() { return new Metrics(active.size(), acceptedOrders, executedTrades, bids.size(), asks.size(), sequence); }
    private NavigableMap<Integer, LinkedHashMap<UUID, Order>> levels(Side side) { return side == Side.BUY ? bids : asks; }

    /** Expensive diagnostic for tests, replay, and benchmark verification; not a per-command hot-path scan. */
    public void assertInvariants() {
        if (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey()) fail("Crossed resting book");
        Set<UUID> seen = new HashSet<>();
        checkLevels(bids, Side.BUY, seen);
        checkLevels(asks, Side.SELL, seen);
        if (!seen.equals(active.keySet())) fail("Active index differs from book");
        if (acceptedOrders != orders.size()) fail("Accepted order count differs");
        for (Order order : orders.values()) {
            if (order.remainingQuantity() < 0 || order.cumulativeFilledQuantity() < 0
                    || Math.addExact(order.remainingQuantity(), order.cumulativeFilledQuantity()) != order.originalQuantity()
                    || order.active() != seen.contains(order.id())
                    || (order.status() == Status.FILLED) != (order.remainingQuantity() == 0)
                    || (order.status() == Status.OPEN && order.cumulativeFilledQuantity() != 0)
                    || (order.status() == Status.PARTIALLY_FILLED && order.cumulativeFilledQuantity() == 0)) fail("Invalid order quantities/status");
        }
    }
    private void checkLevels(NavigableMap<Integer, LinkedHashMap<UUID, Order>> levels, Side side, Set<UUID> seen) {
        for (var entry : levels.entrySet()) {
            if (entry.getValue().isEmpty()) fail("Empty price level");
            long previous = 0;
            for (Order order : entry.getValue().values()) {
                if (!order.active() || order.remainingQuantity() <= 0 || order.side() != side
                        || order.priceTicks() != entry.getKey() || order.sequence() <= previous
                        || !order.equals(orders.get(order.id())) || !order.equals(active.get(order.id()))
                        || !seen.add(order.id())) fail("Invalid price-time queue");
                previous = order.sequence();
            }
        }
    }
    private static void fail(String message) { throw new IllegalStateException(message); }
}
