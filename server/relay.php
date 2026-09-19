<?php
declare(strict_types=1);

/**
 * relay.php — the shared half of the browser-control relay.
 *
 * Required by both entry points: browser.php, which the phone talks to, and mcp.php, which
 * Claude talks to. It owns the database, the tokens and the rules about when a session is
 * alive; neither entry point decides any of that for itself.
 *
 * Configuration comes from constants the entry file defines before requiring this. Only
 * RELAY_DIR has no default, which also means a stray direct request to this file does
 * nothing.
 */

if (!defined('RELAY_DIR')) {
    error_log('relay: RELAY_DIR is not defined. relay.php is not a standalone script.');
    http_response_code(500);
    exit;
}

/** Read a configuration constant, or its default. */
function relay_cfg(string $name, mixed $default): mixed
{
    return defined($name) ? constant($name) : $default;
}

/**
 * How long after its last poll a browser still counts as connected.
 *
 * The phone polls on an adaptive interval that stretches to a few seconds when idle, so this
 * has to be a comfortable multiple of the slowest interval rather than a tight bound.
 */
define('RELAY_CONNECTED_SECONDS', (int) (getenv('RELAY_CONNECTED_SECONDS') ?: 15));

/**
 * How long after its last poll a session is still valid.
 *
 * This is the agent token's lifetime, and tying it to the browser's own liveness is what
 * makes "short-lived" mean something without cutting a long task off mid-way: a session stays
 * valid exactly as long as the browser is there to serve it, and dies shortly after it is not.
 */
define('RELAY_VALID_SECONDS', (int) (getenv('RELAY_VALID_SECONDS') ?: 300));

/**
 * Slowing down token guessing.
 *
 * Eight characters is forty bits, which is out of reach only while guesses are slow. The
 * obvious way to make them slow — refuse after N failures — turns out to lock out the person
 * the token belongs to: agent control clears whenever the app cold starts, so being handed a
 * stale token is the ordinary case, and a few of those would wall off the very request that
 * carries the replacement.
 *
 * So failures buy delay, not refusal. At half a second a guess, forty bits is still hopeless,
 * while someone holding a good token waits a moment and gets in. A hard refusal remains for
 * much higher counts, where the point is no longer guessing but keeping a flood of requests
 * from sitting in sleep() and occupying every worker the pool has.
 */
define('RELAY_SLOW_AFTER', (int) (getenv('RELAY_SLOW_AFTER') ?: 5));
define('RELAY_SLOW_MS', (int) (getenv('RELAY_SLOW_MS') ?: 500));
define('RELAY_BLOCK_AFTER', (int) (getenv('RELAY_BLOCK_AFTER') ?: 50));
define('RELAY_ATTEMPT_WINDOW', (int) (getenv('RELAY_ATTEMPT_WINDOW') ?: 300));

/**
 * Pairing has its own budget.
 *
 * It authenticates nothing and can only ever start a fresh session, so counting it against
 * the guessing budget would mean a guesser could stop the phone from pairing at all — the
 * same mistake as letting failures revoke a session, wearing a different hat.
 */
define('RELAY_PAIR_LIMIT', (int) (getenv('RELAY_PAIR_LIMIT') ?: 60));

/**
 * Token alphabet: Crockford base32, which drops I, L, O and U so nothing in a token can be
 * misread as something else when it is on screen next to a copy button.
 */
const RELAY_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** Characters per token. The agent token is read by a person; the browser token never is. */
const RELAY_AGENT_TOKEN_LEN = 8;     // 40 bits — safe only because guessing is rate limited
const RELAY_BROWSER_TOKEN_LEN = 32;  // 160 bits

// ─── Plumbing ────────────────────────────────────────────────────────────────

function relay_now(): int
{
    return time();
}

/** Emit a JSON body and stop. */
function relay_send(array $body, int $status = 200): never
{
    http_response_code($status);
    header('Content-Type: application/json');
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

/** The request body, decoded. */
function relay_input(): array
{
    $raw = file_get_contents('php://input');
    $in = json_decode((string) $raw, true);
    return is_array($in) ? $in : [];
}

/**
 * The client address.
 *
 * REMOTE_ADDR only. X-Forwarded-For is attacker-controlled unless the immediate peer is known
 * to be your own proxy, and this value gates the rate limiter.
 */
function relay_client_ip(): string
{
    return $_SERVER['REMOTE_ADDR'] ?? '';
}

// ─── Tokens ──────────────────────────────────────────────────────────────────

function relay_token(int $length): string
{
    $out = '';
    $alphabet = RELAY_ALPHABET;
    for ($i = 0; $i < $length; $i++) {
        $out .= $alphabet[random_int(0, strlen($alphabet) - 1)];
    }
    return $out;
}

/**
 * The key the token MACs are computed with, created on first use.
 *
 * It lives beside the database rather than in it, so that a copy of the database alone does
 * not let an 8-character token be recovered by trying all of them.
 */
function relay_secret(): string
{
    $path = RELAY_DIR . '/relay-key';
    $key = @file_get_contents($path);
    if ($key === false || $key === '') {
        $key = bin2hex(random_bytes(32));
        $fh = @fopen($path, 'x');
        if ($fh !== false) {
            fwrite($fh, $key);
            fclose($fh);
            @chmod($path, 0600);
        } else {
            // Lost the race; whoever won wrote the one that counts.
            $key = (string) @file_get_contents($path);
        }
    }
    return trim($key);
}

function relay_mac(string $token): string
{
    return hash_hmac('sha256', strtoupper(trim($token)), relay_secret());
}

// ─── Database ────────────────────────────────────────────────────────────────

function relay_db(): PDO
{
    static $db = null;
    if ($db instanceof PDO) {
        return $db;
    }
    if (!is_dir(RELAY_DIR) && !@mkdir(RELAY_DIR, 0700, true)) {
        relay_send(['error' => 'server', 'message' => 'cannot create data directory'], 500);
    }
    $db = new PDO('sqlite:' . RELAY_DIR . '/relay.sq3');
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $db->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    // WAL lets the phone's poll read while an MCP request is writing, which is the whole
    // contention story here. busy_timeout covers the rest.
    $db->exec('PRAGMA journal_mode = WAL');
    $db->exec('PRAGMA busy_timeout = 5000');
    $db->exec((string) file_get_contents(__DIR__ . '/schema.sql'));
    return $db;
}

// ─── Rate limiting ───────────────────────────────────────────────────────────

function relay_note(string $kind): void
{
    $db = relay_db();
    $db->prepare('INSERT INTO attempts (at, ip, kind) VALUES (?, ?, ?)')
        ->execute([relay_now(), relay_client_ip(), $kind]);
    $db->prepare('DELETE FROM attempts WHERE at < ?')
        ->execute([relay_now() - RELAY_ATTEMPT_WINDOW * 4]);
}

/** How many of these this address has run up inside the window. */
function relay_recent(string $kind): int
{
    $stmt = relay_db()->prepare(
        'SELECT COUNT(*) AS n FROM attempts WHERE ip = ? AND kind = ? AND at >= ?'
    );
    $stmt->execute([relay_client_ip(), $kind, relay_now() - RELAY_ATTEMPT_WINDOW]);
    return (int) $stmt->fetch()['n'];
}

/**
 * Make this address wait for its recent failures, or say it has had enough.
 *
 * Called before looking a token up, so a guesser pays the price whether or not the guess is
 * any good. Returns false when the caller should be refused outright.
 */
function relay_throttle(): bool
{
    $failures = relay_recent('auth');
    if ($failures >= RELAY_BLOCK_AFTER) {
        return false;
    }
    if ($failures >= RELAY_SLOW_AFTER) {
        usleep(RELAY_SLOW_MS * 1000);
    }
    return true;
}

// ─── Sessions ────────────────────────────────────────────────────────────────

/**
 * Look a session up by one of its tokens.
 *
 * $column is 'agent_token_mac' or 'browser_token_mac'. A session is returned only while it is
 * valid: not revoked, and seen recently enough. Anything else is indistinguishable from a
 * wrong token on purpose — there is nothing to be gained by telling a guesser that they found
 * a real token that happens to have expired.
 */
function relay_session_by(string $column, string $token): ?array
{
    if ($token === '') {
        return null;
    }
    $stmt = relay_db()->prepare(
        "SELECT * FROM sessions WHERE $column = ? AND revoked_at IS NULL AND last_seen_at >= ?"
    );
    $stmt->execute([relay_mac($token), relay_now() - RELAY_VALID_SECONDS]);
    $row = $stmt->fetch();
    return $row === false ? null : $row;
}

function relay_is_connected(array $session): bool
{
    return (int) $session['last_seen_at'] >= relay_now() - RELAY_CONNECTED_SECONDS;
}

/**
 * Start a session, ending every earlier one.
 *
 * Issuing is the only way a token comes into existence and it always mints new values, so a
 * token from a previous activation can never come back — and because the old sessions are
 * revoked in the same breath, at most one pairing is ever live.
 */
function relay_open_session(?string $deviceId): array
{
    $db = relay_db();
    $now = relay_now();
    $db->beginTransaction();
    try {
        $db->prepare('UPDATE sessions SET revoked_at = ? WHERE revoked_at IS NULL')
            ->execute([$now]);
        $browserToken = relay_token(RELAY_BROWSER_TOKEN_LEN);
        $agentToken = relay_token(RELAY_AGENT_TOKEN_LEN);
        $db->prepare(
            'INSERT INTO sessions (browser_token_mac, agent_token_mac, device_id,
                                   created_at, last_seen_at)
             VALUES (?, ?, ?, ?, ?)'
        )->execute([
            relay_mac($browserToken), relay_mac($agentToken), $deviceId, $now, $now,
        ]);
        $id = (int) $db->lastInsertId();
        $db->commit();
    } catch (Throwable $e) {
        $db->rollBack();
        throw $e;
    }
    return ['session_id' => $id, 'browser_token' => $browserToken, 'agent_token' => $agentToken];
}

function relay_revoke(int $sessionId): void
{
    relay_db()->prepare('UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL')
        ->execute([relay_now(), $sessionId]);
}

// ─── Commands ────────────────────────────────────────────────────────────────

function relay_enqueue(int $sessionId, array $request): int
{
    $db = relay_db();
    $db->prepare('INSERT INTO commands (session_id, created_at, request) VALUES (?, ?, ?)')
        ->execute([$sessionId, relay_now(), json_encode($request, JSON_UNESCAPED_UNICODE)]);
    return (int) $db->lastInsertId();
}

/** True when this session already has a command out that nobody has answered. */
function relay_has_outstanding(int $sessionId): bool
{
    $stmt = relay_db()->prepare(
        'SELECT COUNT(*) AS n FROM commands WHERE session_id = ? AND done_at IS NULL'
    );
    $stmt->execute([$sessionId]);
    return ((int) $stmt->fetch()['n']) > 0;
}

/** The oldest command this session has not been handed yet, marked as taken. */
function relay_take_next(int $sessionId): ?array
{
    $db = relay_db();
    $db->beginTransaction();
    try {
        $stmt = $db->prepare(
            'SELECT * FROM commands WHERE session_id = ? AND taken_at IS NULL
             ORDER BY id LIMIT 1'
        );
        $stmt->execute([$sessionId]);
        $row = $stmt->fetch();
        if ($row === false) {
            $db->commit();
            return null;
        }
        $db->prepare('UPDATE commands SET taken_at = ? WHERE id = ?')
            ->execute([relay_now(), $row['id']]);
        $db->commit();
    } catch (Throwable $e) {
        $db->rollBack();
        throw $e;
    }
    return $row;
}

function relay_complete(int $sessionId, int $commandId, array $response): bool
{
    $stmt = relay_db()->prepare(
        'UPDATE commands SET done_at = ?, response = ?
         WHERE id = ? AND session_id = ? AND done_at IS NULL'
    );
    $stmt->execute([
        relay_now(), json_encode($response, JSON_UNESCAPED_UNICODE), $commandId, $sessionId,
    ]);
    return $stmt->rowCount() > 0;
}

function relay_response(int $commandId): ?array
{
    $stmt = relay_db()->prepare('SELECT response FROM commands WHERE id = ? AND done_at IS NOT NULL');
    $stmt->execute([$commandId]);
    $row = $stmt->fetch();
    if ($row === false || $row['response'] === null) {
        return null;
    }
    $decoded = json_decode((string) $row['response'], true);
    return is_array($decoded) ? $decoded : ['result' => $row['response']];
}

/** Abandon a command nobody answered, so a later one is not queued behind it forever. */
function relay_abandon(int $commandId): void
{
    relay_db()->prepare(
        'UPDATE commands SET done_at = ?, response = ? WHERE id = ? AND done_at IS NULL'
    )->execute([relay_now(), json_encode(['error' => 'timeout']), $commandId]);
}
