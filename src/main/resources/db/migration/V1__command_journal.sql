CREATE TABLE accepted_commands (
    sequence BIGINT PRIMARY KEY CHECK (sequence > 0),
    kind VARCHAR(6) NOT NULL CHECK (kind IN ('SUBMIT', 'CANCEL')),
    order_id UUID NOT NULL,
    side VARCHAR(4),
    price_ticks INTEGER,
    quantity INTEGER,
    accepted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(accepted_at)),
    CONSTRAINT command_shape CHECK (
        (kind = 'SUBMIT' AND side IS NOT NULL AND side IN ('BUY', 'SELL')
            AND price_ticks IS NOT NULL AND price_ticks BETWEEN 1 AND 100000000
            AND quantity IS NOT NULL AND quantity BETWEEN 1 AND 1000000)
        OR (kind = 'CANCEL' AND side IS NULL AND price_ticks IS NULL AND quantity IS NULL)
    )
);
CREATE UNIQUE INDEX unique_submitted_order_id ON accepted_commands(order_id) WHERE kind = 'SUBMIT';

CREATE TABLE executions (
    id UUID PRIMARY KEY,
    command_sequence BIGINT NOT NULL REFERENCES accepted_commands(sequence),
    execution_index INTEGER NOT NULL CHECK (execution_index >= 0),
    maker_order_id UUID NOT NULL,
    taker_order_id UUID NOT NULL CHECK (taker_order_id <> maker_order_id),
    price_ticks INTEGER NOT NULL CHECK (price_ticks BETWEEN 1 AND 100000000),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000000),
    executed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(executed_at)),
    UNIQUE (command_sequence, execution_index)
);
CREATE INDEX execution_history ON executions(command_sequence DESC, execution_index DESC);

-- Guard accidental mutation. A database owner can still disable triggers; this is not tamper-proof storage.
CREATE FUNCTION reject_journal_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Exchange journal is append-only';
END;
$$;
CREATE TRIGGER immutable_commands BEFORE UPDATE OR DELETE OR TRUNCATE ON accepted_commands
    FOR EACH STATEMENT EXECUTE FUNCTION reject_journal_mutation();
CREATE TRIGGER immutable_executions BEFORE UPDATE OR DELETE OR TRUNCATE ON executions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_journal_mutation();
