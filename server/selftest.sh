#!/bin/sh
# Smoke test for the browser-control relay, against PHP's built-in server.
#
# It exercises the real browser.php, mcp.php and relay.php — not copies — against a scratch
# directory under /tmp. Nothing outside that is touched.
#
#   MCPINNER=/path/to/mcpinner.php ./selftest.sh
#
# mcpinner.php comes from https://github.com/paijp/minimal-mcp. Without it the MCP half is
# skipped and only the device protocol is checked.
#
# Half of what is checked here is the attacks, not the happy path: polling without the
# browser token, driving with a stale agent token, guessing until the rate limiter bites, and
# checking that every command reaches the phone signed with a token the relay never stored.

set -eu

PORT="${PORT:-8731}"
BASE="http://127.0.0.1:$PORT"
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d /tmp/rbmcp-selftest.XXXXXX)
MCPINNER="${MCPINNER:-/var/www/db/mcpinner.php}"

pass=0
fail=0

cleanup() {
    [ -n "${SRV:-}" ] && kill "$SRV" 2>/dev/null || true
    [ -n "${DEV:-}" ] && kill "$DEV" 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

ok() { pass=$((pass + 1)); printf '  ok   %s\n' "$1"; }
no() { fail=$((fail + 1)); printf '  FAIL %s\n' "$1"; [ -n "${2:-}" ] && printf '       %s\n' "$2"; }

# Assert that $2 (a body) contains the literal $3.
#
# A tool's return value is JSON encoded into the text of a JSON-RPC response, so quotes inside
# it arrive backslash-escaped. Dropping backslashes before matching lets an expectation be
# written the way the tool actually returns it.
unescape() { printf '%s' "$1" | tr -d '\\'; }

has() {
    case "$(unescape "$2")" in
        *"$3"*) ok "$1" ;;
        *) no "$1" "expected '$3' in: $2" ;;
    esac
}

hasnt() {
    case "$(unescape "$2")" in
        *"$3"*) no "$1" "did not expect '$3' in: $2" ;;
        *) ok "$1" ;;
    esac
}

post() { # post <path> <json>
    curl -sS -X POST -H 'Content-Type: application/json' -d "$2" "$BASE$1"
}

mcp() { # mcp <bearer> <json-rpc body>
    curl -sS -X POST -H 'Content-Type: application/json' \
        -H "Authorization: Bearer $1" -d "$2" "$BASE/mcp.php"
}

field() { # field <json> <key> -- first string value for that key
    printf '%s' "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}


# The phone's side of the crypto, done with the PHP CLI so the test does not depend on the
# relay's own functions to check the relay.
newtoken() { php -r '$a="0123456789ABCDEFGHJKMNPQRSTVWXYZ";$t="";for($i=0;$i<32;$i++)$t.=$a[random_int(0,31)];echo $t;'; }
sesskey() { php -r 'echo hash("sha256","screennote-session:".$argv[1]);' "$1"; }
pairbody() { printf '{"op":"pair","device_id":"selftest","session_key":"%s"}' "$(sesskey "$1")"; }

# Short timings so the connected/expired paths can be reached without a long wait.
export RELAY_DIR="$WORK/var"
export RELAY_CONNECTED_SECONDS=3
export RELAY_VALID_SECONDS=8
export RELAY_SLOW_AFTER=3
export RELAY_SLOW_MS=50
export RELAY_BLOCK_AFTER=12
export RELAY_ATTEMPT_WINDOW=60
export RELAY_PAIR_LIMIT=60
export RELAY_WAIT_SECONDS=3
export MCP_TOKEN_ALLOW=any
# The relay needs the web server to handle requests concurrently: a tool call sits waiting
# for the phone's answer, and the phone's poll is what brings it. The built-in server is
# single-process unless told otherwise, so without this the two deadlock — which is also
# worth knowing when picking where to deploy it.
export PHP_CLI_SERVER_WORKERS=8
export MCPINNER

mkdir -p "$RELAY_DIR"
php -S "127.0.0.1:$PORT" -t "$HERE" >"$WORK/httpd.log" 2>&1 &
SRV=$!
# Wait for it to accept connections rather than sleeping a guess.
i=0
while [ $i -lt 50 ]; do
    curl -sS -o /dev/null "$BASE/browser.php" 2>/dev/null && break
    i=$((i + 1))
    sleep 0.1
done

echo "== device protocol =="

has "pairing without a session key is refused with an upgrade hint" \
    "$(post /browser.php '{"op":"pair","device_id":"selftest"}')" 'session_key_required'

AT=$(newtoken)
PAIR=$(post /browser.php "$(pairbody "$AT")")
BT=$(field "$PAIR" browser_token)

[ -n "$BT" ] && ok "pair returns a browser token" || no "pair returns a browser token" "$PAIR"
hasnt "pair does not hand out an agent token" "$PAIR" 'agent_token'
hasnt "the relay never sees the agent token" "$(cat "$RELAY_DIR"/relay.sq3* 2>/dev/null | tr -d '\0')" "$AT"

has "poll with the browser token is accepted" \
    "$(post /browser.php "{\"op\":\"poll\",\"browser_token\":\"$BT\",\"state\":{\"url\":\"https://example.com/\",\"title\":\"Example\"}}")" \
    '"command":null'

echo "== the browser token is what keeps an impostor out =="

# This is the attack the whole two-token split exists to stop: anyone who reads the chat log
# has the agent token, and must still not be able to stand in for the browser and answer for
# it. Answering is how you feed a model a page that was never there.
has "polling with the agent token is refused" \
    "$(post /browser.php "{\"op\":\"poll\",\"browser_token\":\"$AT\"}")" 'unknown_token'
has "polling with no token is refused" \
    "$(post /browser.php '{"op":"poll"}')" 'unknown_token'
has "posting a result without the browser token is refused" \
    "$(post /browser.php '{"op":"result","browser_token":"NOPE","command_id":1,"response":{}}')" \
    'unknown_token'

echo "== re-pairing retires the old session =="

AT2=$(newtoken)
PAIR2=$(post /browser.php "$(pairbody "$AT2")")
BT2=$(field "$PAIR2" browser_token)
has "the previous browser token stops working" \
    "$(post /browser.php "{\"op\":\"poll\",\"browser_token\":\"$BT\"}")" 'unknown_token'

BT="$BT2"
AT="$AT2"
post /browser.php "{\"op\":\"poll\",\"browser_token\":\"$BT\",\"state\":{\"url\":\"https://example.com/\",\"title\":\"Example\"}}" >/dev/null

if [ ! -f "$MCPINNER" ]; then
    echo
    echo "MCP half skipped: no mcpinner.php at $MCPINNER"
    echo "  (get it from https://github.com/paijp/minimal-mcp)"
    echo
    printf '%d passed, %d failed\n' "$pass" "$fail"
    [ "$fail" -eq 0 ]
    exit
fi

echo "== MCP registration =="

rm -f "$RELAY_DIR/mcp-hash"
TOKEN=$(field "$(curl -sS -X POST "$BASE/mcp.php/token")" access_token)
[ -n "$TOKEN" ] && ok "a bearer token is issued once" || no "a bearer token is issued once" "none"
has "and only once" "$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp.php/token")" '403'
chmod g+x "$RELAY_DIR/mcp-hash"

LIST=$(mcp "$TOKEN" '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
has "browser_status is listed" "$LIST" 'browser_status'
has "browser_eval is listed" "$LIST" 'browser_eval'
has "browser_log is listed" "$LIST" 'browser_log'
has "descriptions survive the null probe" "$LIST" 'Run JavaScript in the page'
has "agent_token is required" "$LIST" '"required"'
hasnt "listing tools does not open a session" "$LIST" 'agent_token_mac'

call() { # call <tool> <args-json>
    mcp "$TOKEN" "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}"
}

echo "== tools =="

has "status reports the page the browser last polled with" \
    "$(call browser_status "{\"agent_token\":\"$AT\"}")" 'https://example.com/'

has "a wrong agent token says how to recover" \
    "$(call browser_status '{"agent_token":"AAAAAAAA"}')" 'Agent control'

echo "== commands arrive signed =="

# Queue one command and read it off the device endpoint directly, to see what the phone sees.
call browser_eval "{\"agent_token\":\"$AT\",\"js\":\"signed()\"}" >/dev/null 2>&1 &
CALL=$!
sleep 0.5
RAW=$(post /browser.php "{\"op\":\"poll\",\"browser_token\":\"$BT\"}")
wait "$CALL"
php -r '
$r = json_decode($argv[1], true)["command"] ?? null;
if (!$r) { echo "FAIL no command\n"; exit; }
$want = hash_hmac("sha256", $r["id"] . "\n" . $r["request_json"], $argv[2]);
echo hash_equals($want, $r["mac"]) ? "OK\n" : "FAIL bad mac\n";
echo str_contains($r["request_json"], "signed()") ? "OK\n" : "FAIL body\n";
$bad = hash_hmac("sha256", $r["id"] . "\n" . $r["request_json"], "WRONGTOKEN");
echo hash_equals($bad, $r["mac"]) ? "FAIL other key verifies\n" : "OK\n";
' "$RAW" "$AT" >"$WORK/sig"
grep -q '^FAIL' "$WORK/sig" && no "command carries a valid signature" "$(cat "$WORK/sig") $RAW" \
    || ok "command carries a valid signature, over the exact text, and only with this token"

# A fake device: polls, answers any eval with a canned value, posts the result back.
cat >"$WORK/device.sh" <<DEVICE
#!/bin/sh
while true; do
    R=\$(curl -sS -X POST -H 'Content-Type: application/json' \
        -d "{\"op\":\"poll\",\"browser_token\":\"$BT\",\"state\":{\"url\":\"https://example.com/\",\"title\":\"Example\"}}" \
        "$BASE/browser.php")
    ID=\$(printf '%s' "\$R" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
    if [ -n "\$ID" ]; then
        curl -sS -X POST -H 'Content-Type: application/json' \
            -d "{\"op\":\"result\",\"browser_token\":\"$BT\",\"command_id\":\$ID,\"response\":{\"value\":42,\"url\":\"https://example.com/\"}}" \
            "$BASE/browser.php" >/dev/null
    fi
    sleep 0.3
done
DEVICE
chmod +x "$WORK/device.sh"
"$WORK/device.sh" >/dev/null 2>&1 &
DEV=$!
sleep 1

has "eval reaches the browser and the answer comes back" \
    "$(call browser_eval "{\"agent_token\":\"$AT\",\"js\":\"1+1\"}")" '"value": 42'

has "the log is fetched from the browser, not the relay" \
    "$(call browser_log "{\"agent_token\":\"$AT\"}")" '"value": 42'

echo "== a browser that polls but never answers =="

# The command has to be given up on, and — more importantly — giving up has to clear it out of
# the way. An abandoned command left in the queue would block every later one behind it.
kill "$DEV" 2>/dev/null || true
cat >"$WORK/mute.sh" <<MUTE
#!/bin/sh
while true; do
    curl -sS -X POST -H 'Content-Type: application/json' \
        -d "{\"op\":\"poll\",\"browser_token\":\"$BT\",\"state\":{\"url\":\"https://example.com/\"}}" \
        "$BASE/browser.php" >/dev/null
    sleep 0.3
done
MUTE
chmod +x "$WORK/mute.sh"
"$WORK/mute.sh" >/dev/null 2>&1 &
DEV=$!
sleep 0.5

has "a silent browser times out rather than hanging" \
    "$(call browser_eval "{\"agent_token\":\"$AT\",\"js\":\"1+1\"}")" '"error": "timeout"'
hasnt "and the abandoned command does not block the next one" \
    "$(call browser_eval "{\"agent_token\":\"$AT\",\"js\":\"2+2\"}")" 'busy'

echo "== the browser going away =="

kill "$DEV" 2>/dev/null || true
DEV=
sleep "$((RELAY_CONNECTED_SECONDS + 1))"

has "eval refuses rather than hanging once the browser is quiet" \
    "$(call browser_eval "{\"agent_token\":\"$AT\",\"js\":\"1+1\"}")" 'not_connected'

sleep "$((RELAY_VALID_SECONDS - RELAY_CONNECTED_SECONDS + 1))"
has "the agent token expires with the browser that issued it" \
    "$(call browser_status "{\"agent_token\":\"$AT\"}")" 'Agent control'

echo "== rbctl =="

has "rbctl explains itself" "$("$HERE/rbctl" --help)" 'RB_TOKEN'
has "rbctl refuses without a token" "$("$HERE/rbctl" status 2>&1 || true)" 'set RB_TOKEN'
AT4=$(newtoken)
BT4=$(field "$(post /browser.php "$(pairbody "$AT4")")" browser_token)
post /browser.php "{\"op\":\"poll\",\"browser_token\":\"$BT4\",\"state\":{\"url\":\"https://example.org/\"}}" >/dev/null
has "rbctl status reads the session" "$(RB_TOKEN="$AT4" "$HERE/rbctl" status 2>&1)" 'https://example.org/'
has "rbctl marks page output as data" "$(RB_TOKEN="$AT4" "$HERE/rbctl" status 2>&1)" 'not instructions'


echo "== guessing the agent token =="

# The token is long enough now that guessing is hopeless anyway; the limiter stays. What must
# NOT happen is that the slowing-down locks out the person the token belongs to: a stale token
# is the ordinary case here, because agent control clears on every cold start, and the request
# that carries the replacement must still get through.
AT3=$(newtoken)
PAIR3=$(post /browser.php "$(pairbody "$AT3")")
BT3=$(field "$PAIR3" browser_token)
post /browser.php "{\"op\":\"poll\",\"browser_token\":\"$BT3\",\"state\":{\"url\":\"https://example.com/\"}}" >/dev/null

i=0
while [ $i -lt "$RELAY_SLOW_AFTER" ]; do
    call browser_status '{"agent_token":"ZZZZZZZZ"}' >/dev/null
    i=$((i + 1))
done
has "a good token still works after a run of failures" \
    "$(call browser_status "{\"agent_token\":\"$AT3\"}")" 'https://example.com/'

while [ $i -lt "$RELAY_BLOCK_AFTER" ]; do
    call browser_status '{"agent_token":"ZZZZZZZZ"}' >/dev/null
    i=$((i + 1))
done
has "sustained guessing is refused outright" \
    "$(call browser_status '{"agent_token":"ZZZZZZZZ"}')" 'Too many failed attempts'

# And the refusal must not be a way to cut the user off: pairing is the phone's own request
# and has nothing to do with someone else's guesses.
has "pairing still works while a guesser is blocked" \
    "$(post /browser.php "$(pairbody "$(newtoken)")")" 'browser_token'

echo
printf '%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
