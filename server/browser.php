<?php
declare(strict_types=1);

/**
 * browser.php — the endpoint the phone talks to.
 *
 * One URL, one POST, and the operation named in the body. Routing by path would mean rewrite
 * rules in whatever web server this ends up behind; routing by field means the deployment is
 * a single alias and nothing else.
 *
 *   {"op":"pair",   "device_id":"..."}         -> {browser_token, agent_token, session_id}
 *   {"op":"poll",   "browser_token":"...", "state":{...}}
 *                                              -> {command:{id,request}|null}
 *   {"op":"result", "browser_token":"...", "command_id":N, "response":{...}}
 *   {"op":"bye",    "browser_token":"..."}
 *
 * Only `pair` is unauthenticated, and it can do nothing except start a new session of its
 * own: it never joins an existing one. Everything else needs the browser token, which is the
 * point of the whole arrangement — it is the one credential that never passes through a chat
 * log, so nobody who reads that log can take the browser's place and answer for it.
 */

/**
 * Host-local settings, if any.
 *
 * Deployment details — where the data lives, where mcpinner.php was installed, who may ask
 * for a token — differ per host and do not belong in the repository. A config.php beside the
 * entry file may define any of the constants below; whatever it leaves alone falls back to
 * the environment, and then to the value here. The environment fallback exists for
 * selftest.sh, which php-fpm never sees.
 */
if (is_file(__DIR__ . '/config.php')) {
    require __DIR__ . '/config.php';
}

defined('RELAY_DIR') || define('RELAY_DIR', getenv('RELAY_DIR') ?: __DIR__ . '/var');

require __DIR__ . '/relay.php';

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
    relay_send(['error' => 'method_not_allowed'], 405);
}

$in = relay_input();

/** Resolve the browser token, or stop. */
function browser_session(array $in): array
{
    if (!relay_throttle()) {
        relay_send(['error' => 'rate_limited'], 429);
    }
    $session = relay_session_by('browser_token_mac', (string) ($in['browser_token'] ?? ''));
    if ($session === null) {
        relay_note('auth');
        relay_send(['error' => 'unknown_token'], 401);
    }
    return $session;
}

switch ((string) ($in['op'] ?? '')) {
    case 'pair':
        // Counted separately from failed authentications: this is the phone's own request and
        // must not be squeezed out by someone else guessing tokens at the same address.
        if (relay_recent('pair') >= RELAY_PAIR_LIMIT) {
            relay_send(['error' => 'rate_limited'], 429);
        }
        relay_note('pair');
        $session = relay_open_session(isset($in['device_id']) ? (string) $in['device_id'] : null);
        relay_send($session + [
            'connected_seconds' => RELAY_CONNECTED_SECONDS,
            'valid_seconds' => RELAY_VALID_SECONDS,
        ]);

        // no break — relay_send exits

    case 'poll':
        $session = browser_session($in);
        // The poll is the heartbeat, and carrying the page's state on it means browser_status
        // can be answered out of the database with no round trip to the phone at all.
        $state = is_array($in['state'] ?? null) ? $in['state'] : [];
        relay_db()->prepare(
            'UPDATE sessions SET last_seen_at = ?, url = ?, title = ?, state_json = ? WHERE id = ?'
        )->execute([
            relay_now(),
            isset($state['url']) ? (string) $state['url'] : ($session['url'] ?? null),
            isset($state['title']) ? (string) $state['title'] : ($session['title'] ?? null),
            $state === [] ? $session['state_json'] : json_encode($state, JSON_UNESCAPED_UNICODE),
            $session['id'],
        ]);

        $command = relay_take_next((int) $session['id']);
        relay_send([
            'command' => $command === null ? null : [
                'id' => (int) $command['id'],
                'request' => json_decode((string) $command['request'], true),
            ],
        ]);

    case 'result':
        $session = browser_session($in);
        // Scoped to this session, so a command can only ever be answered by the browser it
        // was queued for.
        $ok = relay_complete(
            (int) $session['id'],
            (int) ($in['command_id'] ?? 0),
            is_array($in['response'] ?? null) ? $in['response'] : ['result' => $in['response'] ?? null],
        );
        relay_send(['ok' => $ok]);

    case 'bye':
        $session = browser_session($in);
        relay_revoke((int) $session['id']);
        relay_send(['ok' => true]);

    default:
        relay_send(['error' => 'unknown_op'], 400);
}
