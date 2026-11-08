-- Browser-control relay: the whole persistent state.
--
-- Two parties meet here and neither can reach the other directly: Claude speaks MCP over
-- HTTPS, the phone is behind NAT. A session is one pairing between them, and it is the only
-- thing that binds a command posted by one to a poll made by the other.

PRAGMA journal_mode = WAL;

-- One row per "agent control" activation. At most one is live: issuing a session revokes
-- every earlier one, so a token from a previous activation cannot be replayed.
CREATE TABLE IF NOT EXISTS sessions (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,

    -- HMAC-SHA256 of each token, keyed by a secret kept outside the database. Plain hashing
    -- would not be worth much here: the agent token is 40 bits, so a stolen database would
    -- fall to an offline sweep in minutes. The key is what makes that infeasible.
    browser_token_mac  TEXT NOT NULL UNIQUE,
    agent_token_mac    TEXT NOT NULL UNIQUE,

    -- Identifies the device across activations. Recorded rather than enforced: it is a clue
    -- for the operator, not a credential.
    device_id          TEXT,

    created_at         INTEGER NOT NULL,
    -- Every poll stamps this. It is what "the browser is still there" means, and therefore
    -- also what the agent token's lifetime is tied to.
    last_seen_at       INTEGER NOT NULL,
    revoked_at         INTEGER,

    -- Last state the browser reported, so browser_status can answer without a round trip —
    -- and can still say something useful once the browser has gone away.
    url                TEXT,
    title              TEXT,
    state_json         TEXT
);

CREATE INDEX IF NOT EXISTS sessions_live ON sessions (revoked_at, last_seen_at);

-- The work queue. One row per command; the response is written back into the same row, which
-- is what the waiting MCP request polls for.
CREATE TABLE IF NOT EXISTS commands (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  INTEGER NOT NULL REFERENCES sessions (id),
    created_at  INTEGER NOT NULL,
    taken_at    INTEGER,
    done_at     INTEGER,
    request     TEXT NOT NULL,
    response    TEXT
);

CREATE INDEX IF NOT EXISTS commands_pending ON commands (session_id, taken_at, id);

-- Failed authentication attempts, for rate limiting.
--
-- An 8-character agent token is 40 bits, which is only out of reach of a guessing attack
-- while the guessing is slowed down. This table is therefore load-bearing, not a nicety.
-- Note what it does NOT do: repeated failures never revoke the session. Doing that would
-- hand anyone who can reach the endpoint a way to cut the user off by guessing badly.
CREATE TABLE IF NOT EXISTS attempts (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    at       INTEGER NOT NULL,
    ip       TEXT NOT NULL,
    kind     TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS attempts_recent ON attempts (ip, at);
