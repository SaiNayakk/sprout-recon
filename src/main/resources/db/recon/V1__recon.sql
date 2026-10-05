-- Reconciliation runs and what each check found.
CREATE TABLE runs (
    id           uuid PRIMARY KEY,
    trigger      text NOT NULL CHECK (trigger IN ('SCHEDULED', 'ON_DEMAND')),
    session      date,
    status       text CHECK (status IN ('PASS', 'FAIL', 'ERROR')),
    started_at   timestamptz NOT NULL,
    finished_at  timestamptz
);

CREATE UNIQUE INDEX one_scheduled_run_per_session ON runs (session) WHERE trigger = 'SCHEDULED';

CREATE TABLE checks (
    run_id       uuid NOT NULL REFERENCES runs (id),
    name         text NOT NULL,
    status       text NOT NULL CHECK (status IN ('PASS', 'FAIL', 'ERROR')),
    summary      text NOT NULL,
    differences  text NOT NULL DEFAULT '[]',     -- JSON array of strings, at most 20
    PRIMARY KEY (run_id, name)
);
