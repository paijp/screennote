# The relay

The VPS half of agent control: Claude speaks MCP to one end, the phone polls the other, and
neither can reach the other directly. Nothing here is Screennote-specific except the wording
of the error messages; it lives in this repository because the protocol and its only client
are easier to keep honest when they change together.

```
[claude.ai] ──MCP──> mcp.php ─┐
                              ├─ relay.sq3 ─┐
[Screennote] ──poll──> browser.php ─────────┘
```

| | |
| --- | --- |
| `relay.php` | Database, tokens, and when a session is alive. Required by both entry points. |
| `browser.php` | What the phone talks to: `pair`, `poll`, `result`, `bye`. |
| `mcp.php` | What Claude talks to. A [minimal-mcp](https://github.com/paijp/minimal-mcp) entry file. |
| `schema.sql` | Applied on every connection; safe to re-run. |
| `selftest.sh` | Smoke test against PHP's built-in server. Touches nothing outside `/tmp`. |

## Two tokens, and why

Turning on agent control mints a fresh pair and retires every earlier one, so at most one
pairing is ever live and no token can be reissued.

- **The browser token** authenticates the phone. It never leaves the device.
- **The agent token** is shown to the user, pasted into the conversation, and passed to every
  tool. Eight characters, because a person reads it off a screen.

The split is not symmetry for its own sake. Whoever holds the agent token can *drive* the
browser — and everything they do happens in the real browser, in front of the user, in the
Picture-in-Picture window. Whoever holds the browser token can *answer* for the browser: tell
Claude what a page says. That is unlimited prompt injection, and the PiP window shows nothing,
because the real browser was never asked. The more dangerous of the two is the one that is
kept out of the chat log.

An eight-character token is forty bits. That is out of reach only while guessing is slow, so
the throttling in `relay.php` is load-bearing rather than decorative. It is deliberately a
delay and not a refusal: agent control clears whenever the app cold starts, so being handed a
stale token is the ordinary case, and a wall built out of failures would block the very
request that carries the replacement. Nor does it ever revoke a session or stop pairing —
either would hand anyone who can reach the endpoint a way to cut the user off by guessing
badly.

## What an agent can read

`browser_log` returns the browser's own log — which is where everything the page cannot say
ends up: a navigation refused, a certificate rejected, a link handed to another app. Without
it the only route to that is the user copying the log out by hand, which is the friction this
whole arrangement exists to remove.

Two limits are part of the design rather than tidiness.

**It starts at the handover.** The log holds every address visited since the app started.
Handing over one page is not handing over where the user has been, so the session records
where the log stood when agent control went on and never reads back past it.

**`console` is the page's own words.** It is excluded unless asked for by name, and labelled
when returned. A page writes its console and can write anything there, including text aimed at
whoever reads it — the same channel as page content, but arriving dressed as the browser's own
output, which is the part worth being explicit about. `browser_eval` attaches the console
output from its own run for the same reason and with the same label.

## The agent token's lifetime

A session is valid while the browser is there to serve it: every poll stamps `last_seen_at`,
and the session expires shortly after the polls stop. Nothing is pinned to an absolute clock,
so a long task is never cut off mid-way and an abandoned one does not linger.

## Install

Requires PHP 8.1+ with `pdo_sqlite`, and a web server that handles requests **concurrently** —
a tool call sits waiting for the phone's answer and the phone's poll is what brings it, so a
single-process server deadlocks. nginx with php-fpm is fine; `php -S` needs
`PHP_CLI_SERVER_WORKERS`.

```sh
install -d -m 755 /opt/rbmcp
install -d -m 700 /opt/rbmcp/var          # owned by the web server user
curl -sSLo /opt/rbmcp/mcpinner.php \
    https://raw.githubusercontent.com/paijp/minimal-mcp/main/mcpinner.php
for f in relay.php browser.php mcp.php schema.sql; do
    curl -sSLo "/opt/rbmcp/$f" \
        "https://raw.githubusercontent.com/paijp/screennote/main/server/$f"
done
```

Deployment details that differ per host — where the data lives, who may ask for a token —
go in a `config.php` beside the entry files. It is not in the repository, and whatever it
does not define falls back to the environment (which is how `selftest.sh` runs the real files
against a scratch directory) and then to the built-in default.

```php
<?php
const RELAY_DIR = '/opt/rbmcp/var';
const MCP_TOKEN_ALLOW = ['160.79.104.0/21'];   // [] to disable, e.g. while testing with curl
```

`var/` must be writable by the web server user.

Serving it needs two aliases and nothing else — there are no rewrite rules, because
`browser.php` routes on a field in the body rather than on the path:

```
/rbmcp/browser  -> /opt/rbmcp/browser.php
/rbmcp/mcp*     -> /opt/rbmcp/mcp.php      (mcpinner routes /token and /authorize itself)
```

Registering the connector follows minimal-mcp: delete `var/mcp-hash`, add the connector, then
`chmod g+x var/mcp-hash` to let tools run. See that project's README.

## Testing

```sh
MCPINNER=/path/to/mcpinner.php ./selftest.sh
```

Half of what it checks is the attacks: polling with the agent token, answering for a browser
without its token, reusing a retired token, and guessing until the throttle bites — then
confirming that a good token still works and that pairing was not collateral damage. A suite
that only walked the happy path would pass just as happily with the two tokens swapped.
