<?php
declare(strict_types=1);

/**
 * agent.php — driving the browser, shared by the two ways Claude can reach it.
 *
 * mcp.php serves a connector; rbctl serves a Claude that has a shell on this host instead.
 * Both resolve an agent token to a session and queue signed commands the same way, so the
 * rules live here once. Requires relay.php.
 */

/**
 * How long a command waits for the phone. mcp.php sets this below its web server's response
 * limit; rbctl, with no such limit, can afford longer.
 */
defined('RELAY_WAIT_SECONDS') || define('RELAY_WAIT_SECONDS', 25);

/**
 * Resolve a session, or stop with an error a model can act on.
 *
 * The message matters more than it looks. Agent control clears whenever the app cold starts,
 * so a token going stale mid-conversation is the normal case, not an edge one — and a model
 * that is told only "unauthorized" will try the same call again instead of asking the user
 * to fetch a new token.
 */
function rb_session(?string $agentToken): array
{
    if (!relay_throttle()) {
        throw new RuntimeException(
            'Too many failed attempts from this address. Wait a few minutes before retrying.'
        );
    }
    $session = relay_session_by('agent_token_mac', relay_session_key((string) $agentToken));
    if ($session === null) {
        relay_note('auth');
        throw new RuntimeException(
            'This agent token is not valid. It expires when the browser stops running or '
            . 'agent control is switched off. Ask the user to turn "Agent control" on again '
            . 'in Screennote and paste the new token.'
        );
    }
    return $session;
}

function rb_state(array $session): array
{
    $state = json_decode((string) ($session['state_json'] ?? ''), true);
    return [
        'connected' => relay_is_connected($session),
        'seconds_since_seen' => relay_now() - (int) $session['last_seen_at'],
        'url' => $session['url'],
        'title' => $session['title'],
        'state' => is_array($state) ? $state : null,
    ];
}

/**
 * Send a command and wait for the browser to answer it.
 *
 * Shared by every tool that needs the phone rather than the database, and by rbctl.
 */
function rb_command(array $session, string $agentToken, array $request)
{
    if (!relay_is_connected($session)) {
        return rb_state($session) + [
            'error' => 'not_connected',
            'message' => 'The browser has not polled recently. Ask the user to bring '
                . 'Screennote to the foreground, then try again.',
        ];
    }
    // One command at a time. Two holders of the same token would otherwise interleave
    // silently; this way the second one gets told, and so does the user.
    if (relay_has_outstanding((int) $session['id'])) {
        return [
            'error' => 'busy',
            'message' => 'Another command is already in flight for this session.',
        ];
    }

    $commandId = relay_enqueue((int) $session['id'], $request, $agentToken);
    $deadline = relay_now() + RELAY_WAIT_SECONDS;
    while (relay_now() < $deadline) {
        $response = relay_response($commandId);
        if ($response !== null) {
            return $response;
        }
        usleep(100_000);
    }

    relay_abandon($commandId);
    return [
        'error' => 'timeout',
        'message' => 'The browser did not answer within ' . RELAY_WAIT_SECONDS . ' seconds. '
            . 'Call browser_status to see whether it is still connected.',
    ] + rb_state($session);
}
