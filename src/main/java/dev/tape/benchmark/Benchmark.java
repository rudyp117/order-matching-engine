package dev.tape.benchmark;

import dev.tape.domain.*;
import dev.tape.engine.MatchingEngine;
import java.time.Instant;
import java.util.*;

/** Seeded wall-clock harness, not JMH and not end-to-end exchange performance. */
public final class Benchmark {
    private static final long SEED = 20261027L;
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    public static void main(String[] args) {
        int operations = args.length == 0 ? 30_000 : Integer.parseInt(args[0]);
        if (operations < 10_000 || operations > 600_000 || operations % 6 != 0) {
            throw new IllegalArgumentException("Operations must be 10000..600000 and divisible by 6");
        }
        int warmup = 12_000;
        List<Command> warmupCommands = workload(warmup);
        MatchingEngine warm = new MatchingEngine();
        for (Command command : warmupCommands) warm.apply(command);
        warm.assertInvariants();
        List<Command> commands = workload(operations); // allocation and generation outside measured region
        MatchingEngine engine = new MatchingEngine();
        long[] latencies = new long[operations];
        long begin = System.nanoTime();
        for (int i = 0; i < commands.size(); i++) {
            long start = System.nanoTime();
            engine.apply(commands.get(i));
            latencies[i] = System.nanoTime() - start;
        }
        long elapsed = System.nanoTime() - begin;
        engine.assertInvariants();
        if (engine.metrics().restingOrders() != 0) throw new AssertionError("Workload must finish empty");
        double average = Arrays.stream(latencies).average().orElseThrow() / 1_000.0;
        Arrays.sort(latencies);
        double p95 = latencies[(int) Math.ceil(operations * 0.95) - 1] / 1_000.0;
        System.out.println("workload=seeded six-command FIFO/partial-fill/sweep/cancel cycles; core apply only");
        System.out.println("seed=" + SEED);
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        System.out.println("vm=" + System.getProperty("java.vm.name"));
        System.out.println("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        System.out.println("available_processors=" + Runtime.getRuntime().availableProcessors());
        System.out.println("max_heap_bytes=" + Runtime.getRuntime().maxMemory());
        System.out.println("warmup_commands=" + warmup);
        System.out.println("measured_commands=" + operations);
        System.out.println("accepted_orders=" + engine.metrics().acceptedOrders());
        System.out.println("executed_trades=" + engine.metrics().executedTrades());
        System.out.printf(Locale.ROOT, "elapsed_seconds=%.6f%ncommands_per_second=%.2f%naverage_latency_us=%.3f%np95_latency_us=%.3f%n",
                elapsed / 1e9, operations / (elapsed / 1e9), average, p95);
        System.out.println("limits=no HTTP, database, service lock, state copying, network, or disk; shallow book; includes timers and JVM effects");
    }
    private static List<Command> workload(int count) {
        Random random = new Random(SEED);
        List<Command> result = new ArrayList<>(count);
        for (int cycle = 0; cycle < count / 6; cycle++) {
            int price = 10_000 + random.nextInt(21);
            int q = 1 + random.nextInt(30);
            UUID first = new UUID(1, cycle * 5L + 1);
            UUID second = new UUID(1, cycle * 5L + 2);
            UUID taker = new UUID(1, cycle * 5L + 3);
            UUID higher = new UUID(1, cycle * 5L + 4);
            UUID sweep = new UUID(1, cycle * 5L + 5);
            add(result, Command.Kind.SUBMIT, first, Side.SELL, price, q);
            add(result, Command.Kind.SUBMIT, second, Side.SELL, price, q + 2);
            add(result, Command.Kind.SUBMIT, taker, Side.BUY, price, q + 1);
            add(result, Command.Kind.SUBMIT, higher, Side.SELL, price + 1, q);
            add(result, Command.Kind.SUBMIT, sweep, Side.BUY, price + 1, q * 2 + 2);
            add(result, Command.Kind.CANCEL, sweep, null, null, null);
        }
        return result;
    }
    private static void add(List<Command> commands, Command.Kind kind, UUID id, Side side, Integer price, Integer quantity) {
        commands.add(new Command(commands.size() + 1L, kind, id, side, price, quantity, TIME));
    }
}
