# Mineflayer

A Minecraft plugin for servers.

## Supported platforms

Built against the **Spigot API**, so it runs on:

- Bukkit
- Spigot
- Paper
- Purpur and the other forks

One jar supports both the **26.1.x** and **26.2.x** lines.

## Building

Builds happen exclusively on GitHub Actions, on a push to the repository.

```bash
git push
```

Then download the artifact from the **Actions** tab.

## Commands

| Command | What it does |
|---|---|
| `/mineflayer m start` | Join the fake player to this server |
| `/mineflayer m stop` | Remove it from this server |
| `/mineflayer m connectto <ip> <port> start` | Send a bot to another server |
| `/mineflayer m connectto <ip> <port> stop` | Disconnect the bot from the other server |

`/mf` works as a shorthand for `/mineflayer`. Permission: `mineflayer.command` (op by
default).

## The fake player

The player is registered through `PlayerList.placeNewPlayer`, the same entry point a
real login uses. That is why it shows up in `Bukkit.getOnlinePlayers()`, raises the
player count by `+1`, and keeps the server out of its idle state. It is put in
`SPECTATOR` mode, so it has no body and nothing can interact with it, and it is tied to
no real player.

Server internals (NMS) are reached by reflection rather than a compile-time dependency,
and that is what keeps one jar working on Bukkit, Spigot, Paper and the forks, and on
both the 26.1.x and 26.2.x lines. Methods are looked up **by shape** (parameter count
and types) rather than by literal signature, because Minecraft changes signatures
between versions.

The trade-off is that any mismatch surfaces at runtime rather than at build time, so
failures are reported to whoever ran the command and logged to the console.

### Keeping the fake player from being kicked

The player leaves only on `/mineflayer m stop`. Three layers guarantee that:

1. **Prevention** — every server timer that ends in a kick is reset twice a second: the
   keepalive timeout, the client-loading timeout, the idle timeout
   (`player-idle-timeout`), and the flight checks. A timer that never reaches its limit
   never kicks.
2. **Blocking** — `FakePlayerGuard` cancels `PlayerKickEvent`, which is the path `/kick`,
   other plugins and anti-cheats all go through. The priority is `MONITOR` on purpose:
   to have the last word, so no other plugin can cancel the cancellation.
3. **Recovery** — if something does manage to remove it anyway (an NMS-level disconnect
   fires no cancellable event) it is re-registered **on the very next tick** — a fraction
   of a second. It is not re-added inside the quit event itself, because the server is
   still carrying out the removal at that point, so waiting one tick is the fastest safe
   join possible.
   **And it never gives up:** if re-registration fails the attempts back off (half a
   second up to a minute) and do not stop. The reasons a join fails are mostly
   temporary — a world that has not finished loading, or another plugin throwing inside
   a join listener — and a player that gave up an hour ago is no different from one that
   was never started.

### No body, and untouched by commands

The player is in `SPECTATOR` mode, so it has no body, no collision and no interaction,
and on top of that `invulnerable` and `collidable = false` are set explicitly and
reapplied twice a second, so no attempt to change them survives.

The events that touch it without removing it are cancelled too: damage, teleports, and
any game-mode change away from `SPECTATOR`. **Any command that mentions its name** is
also cancelled — from a player, the console or a command block — except the plugin's own
commands (`/mineflayer` and `/mf`), which are the one intended way out.

The deliberate exception: a command using a selector such as `/kill @a` is not
cancelled, because cancelling every command containing a selector would break the server
for its real players in order to protect one fake one. There is no need for it anyway:
the selector's own effects are cancelled one by one by the events above.

Latency is also set to a sane fixed value rather than zero, because zero is the clearest
sign there is no real connection behind the player.

Bans and the whitelist do not stop recovery: `placeNewPlayer` never consults
`canPlayerLogin` at all, so even a ban command is cancelled as a kick event first, and if
one did go through the player would be re-registered afterwards. This is confirmed by
inspecting the server jar, not inferred.

All three layers stop the moment the stop command runs, which is the one intended way
out.

### A new identity on every join

The player joins **every time** under a fully random name (12 characters) and a different
UUID — the first join from the command and every automatic re-registration after any
removal. **There is no setting for this and no way to turn it off;** it is how the plugin
behaves.

And the cost is stated plainly: the player owns no lasting identity. Every join writes a
fresh file under `world/playerdata` that is never reused, and nothing keyed to a UUID — a
permission, a place on a whitelist — carries from one join to the next. The name in
command messages and the console changes every time, because `name()` returns the name
actually in use rather than a constant.

The command shield follows the new name with no changes, because `FakePlayerGuard` reads
`manager.name()` every time rather than once.

## The remote bot

`/mf m connectto <ip> <port> start` joins a bot to **another server**, and that server
does not need any plugin installed.

The reason is that the bot is not a local registration like the fake player above, but a
**real client connection**: the plugin opens a socket to the target server and speaks the
Minecraft protocol the way any client speaks it — Handshake, then Login, then
Configuration, then Play. The target sees an ordinary player joining, which is why it
does not need to know anything about us.

The scope is **connect and stay**: the bot answers keepalives and pings, confirms
teleports, reconnects automatically if it drops, and relays the target's messages to your
server's console. It does not move, build or fight.

### Keeping the remote bot from being kicked

On a remote server we cannot cancel events the way we do locally, so the only protection
available is **not to give the server a reason**. The bot closes off every automatic kick
path:

| Path | What the bot does |
|---|---|
| `player-idle-timeout` | `player_input` every 15 seconds |
| Client-loading timeout | `player_loaded` as soon as the play phase begins |
| Keepalive timeout | Answers `keep_alive` and `ping` |
| Flight checks | `move_player_status_only` every second with the "on ground" flag |
| Repeated death | Automatic respawn on `set_health ≤ 0` or `player_combat_kill` |
| Resource-pack refusal | Accepts it and reports it loaded, in both the configuration and play phases |
| Non-vanilla client detection | `minecraft:brand = vanilla` and default client settings |

The detail that matters most in the table above is the **idle timeout**, the most common
reason a silent bot gets kicked. Answering keepalives is not enough: by inspection of the
server jar, nothing resets `lastActionTime` except a genuine position change or a
`player_input` packet. We chose `player_input` specifically because it resets the timer
**without claiming any position**, so it cannot fail a movement or flight check.

#### What stays exposed — honestly

Automatic kicks are closed off, but **a human decision cannot be**. If the target's admin
logs in and runs `/ban`, or adds your name to a ban list, or turns on a whitelist that
does not include you, or blocks your server's address — the bot is out. Nothing prevents
that, because we do not own that server and cannot cancel its events. Any claim otherwise
would be a lie.

For the same reason, on the target the bot is **an entirely ordinary player**: that
server's commands affect it the way they affect any player — it can be teleported,
killed, and have its game mode changed. The command protection in the previous section
applies to the local player alone, because it is built on cancelling Bukkit events, which
is something we only have on our own server.

What we do guarantee: the bot will not leave **because of anything it did or failed to
do**. And if it is removed anyway it comes back at once, and if the target keeps refusing
for long enough the local player returns in the meantime so you are never left with no
player.

### The handoff with the local player

When the bot **successfully** joins the target, the local fake player leaves, and on
`stop` it comes back. The order is deliberate: the local player is not removed before the
remote join is confirmed, so you do not lose both if the target refuses the connection.

### Reconnecting

**If the bot leaves the target after having settled there — kicked, restarted, or
dropped — it is rejoined immediately with no wait at all.** That is the intent: the
sending server watches the bot, and any departure that did not come from a `stop` command
is answered with a reconnect right away.

Anything that did not settle — a connection that never got in, or got in and was thrown
out within seconds — backs off progressively from one second up to thirty, and **does not
stop** for as long as the bot is meant to be running. The two cases are told apart by
session length (ten seconds), and that is necessary: a target that kicks on sight also
reaches the play phase, for a second, and without this condition the plugin would
reconnect endlessly at whatever speed the network allows.

The reasons a target refuses are mostly temporary — a server restarting, asleep, or
full — and a bot that gave up ten minutes ago is no different from one that was never
started. The backoff ceiling is what keeps the console from filling up, and repeated
failures are logged once every twenty attempts rather than on every one.

When the backoff reaches its ceiling the local fake player returns automatically while the
bot keeps trying in the background; if the target comes back and the bot joins, the local
one leaves again.

A connection that reaches the play phase resets the counter, so a bot that lived a long
time and then dropped once does not inherit an old failure.

### The account

Read from `config.yml`:

| Key | Purpose |
|---|---|
| `remote.username` | The premium account's name — ignored on an `online-mode=false` target |
| `remote.access-token` | A Microsoft account token — for `online-mode=true` targets only |
| `remote.uuid` | The account's uuid, required alongside the token |
| `remote.relay-chat` | Relay the target's messages to the console |

**An `online-mode=false` target:** leave `access-token` empty, and nothing else is
needed — the bot generates its own name and uuid for every attempt and the target takes
them at face value.

**An `online-mode=true` target:** a real Microsoft account that owns that name is
required, with its token and uuid. **There is no way around this:** the target server asks
Mojang, and the verification happens at Mojang rather than at the target, so nothing in
this plugin can stand in for it. You cannot impersonate an account you do not own. Tokens
are short-lived, so if the target starts reporting a session failure, the token has
expired.

Treat `config.yml` as a secret file the moment a token goes into it.

#### A new identity on every connection

The bot joins the target on **every attempt** — the first one and every reconnect — under
a fully random name and a random UUID, so a name that was refused is never presented
twice. **There is no setting for this.**

The one exception is a premium account, and it is a forced exception rather than an
optional one: the token authenticates one specific name, so a generated name would be
rejected by Mojang on every attempt and the bot would never get in. So it is reported once
in the console that the account will keep its own name.

And the cost: no inventory, no position, no permission and no place on a whitelist carries
from one join to the next, and every join is a stranger to the target.

Honestly about its limits: it gets past a ban on a **name**, and does nothing about a ban
on an **address**, which is what a target resorts to when it notices names that never
repeat.

### The protocol

Packet ids are extracted from the server at runtime through
`ProtocolInfo$Details.listPackets`, not hard-coded. That is what keeps one jar valid on
both 26.1.x and 26.2.x: the numbers genuinely differ between the two lines, and a stale
hard-coded table would have sent well-formed packets under wrong ids — a silent, confusing
failure, worse than an obvious one.

Packets the bot does not need have their bytes skipped without being decoded. That is not
a shortcut but what makes the feature hold up: the only packet difference between 26.1 and
26.2 falls inside a packet we skip.

## Details

- **Name:** Mineflayer
- **Author:** DevGBX9
- **Package:** `com.devgbx9.mineflayer`
- **Java:** 25

## Structure

```
src/main/java/com/devgbx9/mineflayer/Mineflayer.java          - the plugin class
src/main/java/com/devgbx9/mineflayer/MineflayerCommand.java   - the commands
src/main/java/com/devgbx9/mineflayer/FakePlayerManager.java   - the fake player
src/main/java/com/devgbx9/mineflayer/FakePlayerGuard.java     - kick blocking
src/main/java/com/devgbx9/mineflayer/NmsReflect.java          - reflection helpers
src/main/java/com/devgbx9/mineflayer/RandomIdentity.java      - random name generation
src/main/java/com/devgbx9/mineflayer/remote/RemoteBotManager.java     - bot lifecycle
src/main/java/com/devgbx9/mineflayer/remote/RemoteBotConnection.java  - protocol phase machine
src/main/java/com/devgbx9/mineflayer/remote/PacketIds.java            - runtime packet ids
src/main/java/com/devgbx9/mineflayer/remote/FrameCodec.java           - framing, compression, encryption
src/main/java/com/devgbx9/mineflayer/remote/PacketBuf.java            - reading and writing types
src/main/java/com/devgbx9/mineflayer/remote/MojangAuth.java           - premium authentication
src/main/java/com/devgbx9/mineflayer/remote/BotAccount.java           - the bot's identity
src/main/resources/plugin.yml                                 - plugin metadata
src/main/resources/config.yml                                 - fake player and remote bot settings
```

