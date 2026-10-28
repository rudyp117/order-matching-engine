package dev.tape.application;

import dev.tape.domain.*;
import java.util.List;

/** append must commit command and executions together, or throw. No production in-memory adapter. */
public interface Journal {
    record Entry(Command command, List<Trade> executions) {
        public Entry { executions = List.copyOf(executions); }
    }
    List<Entry> readAll();
    void append(Command command, List<Trade> executions);
    List<Trade> recentTrades(int limit);
}
