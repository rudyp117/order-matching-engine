package dev.tape.domain;

import java.util.List;

public record CommandResult(Order order, List<Trade> executions, long commandSequence) {
    public CommandResult { executions = List.copyOf(executions); }
}
