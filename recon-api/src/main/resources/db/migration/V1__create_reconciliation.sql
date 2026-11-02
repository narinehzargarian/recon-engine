CREATE TABLE reconciliation (
    id              UUID PRIMARY KEY,
    left_name       TEXT NOT NULL,
    right_name      TEXT NOT NULL,
    left_count      INTEGER NOT NULL CHECK (left_count >= 0),
    right_count     INTEGER NOT NULL CHECK (right_count >= 0),
    unmatched_left  INTEGER NOT NULL CHECK (unmatched_left >= 0),
    unmatched_right INTEGER NOT NULL CHECK (unmatched_right >= 0),
    result          JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX reconciliation_created_at ON reconciliation (created_at DESC);
