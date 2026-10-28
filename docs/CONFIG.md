# Sintesi configuration

Sintesi reads `config.yml` and `messages.yml` from its data folder (`plugins/sintesi` on Velocity, `plugins/Sintesi` on BungeeCord and Paper). Missing keys are added automatically on start and on `/sintesi reload`: your values are kept and the previous file is saved as `config.yml.old`. Unknown keys stop the plugin with an error, so typos are caught early.

Addresses: "address" means the exact client IP (IPv4 /32, IPv6 /56). "Subnet" means IPv4 /24 or IPv6 /48.

## language

`language: en` (default) or `it`. Chooses the language `messages.yml` is created in. Your own edits in `messages.yml` always win; to switch language on an existing server, delete `messages.yml` and run `/sintesi reload`.

## gate

Limits applied to every new connection before it reaches your server.

| Key | Default | Meaning |
|---|---|---|
| `table-size-log2` | 16 | Size of the internal rate tables (2 to the power of this value). Raise it only for very large networks. Range 8 to 22. |
| `global-burst` | 2000 | How many new, unverified players may enter verification in a burst. |
| `global-per-second` | 1000 | How many new, unverified players may enter verification per second over time. Players who are already verified never use this budget. Above it, new players are asked to try again in a few seconds (`kick.overloaded`). Each admitted verification sends up to about 25 KB, so at the full rate a flood can use up to about 25 MB/s (200 Mbit/s) of upload; lower this value on servers with a small uplink. |
| `subnet-burst` | 8 | Connections a single subnet may open in a burst. Extra connections are closed at once. |
| `subnet-per-second` | 3 | Connections a single subnet may open per second over time. |
| `max-concurrent-per-subnet` | 6 | Open connections a single subnet may hold before the handshake. |
| `status-burst` | 2000 | Server list pings accepted in a burst, for the whole server. |
| `status-per-second` | 1000 | Server list pings accepted per second, for the whole server. Pings over this budget are closed. |
| `max-limbo-sessions` | 5000 | Verifications that may run at the same time. Each one uses a small amount of memory (about 20 KB). |
| `status-cache` | true | Sintesi keeps a copy of the server list response your server sends (per version and hostname) and answers pings itself during an attack, or when `status-per-second` is used up, so the server list stays online without loading your server. The copy is at most 5 seconds old during an attack and is never used when older than 60 seconds. |
| `cached-status-burst` | 10000 | Pings Sintesi may answer from the cache in a burst. |
| `queue-size` | 10000 | New players over `global-per-second` or `max-limbo-sessions` wait in line on the login screen instead of being refused, up to this many. When the line is full they get `kick.overloaded`. 0 turns the line off. |
| `queue-seconds` | 15 | Longest wait in line before `kick.overloaded`. Range 1 to 25 (the game client gives up after about 30 seconds). |
| `cached-status-per-second` | 5000 | Pings Sintesi may answer from the cache per second. A response with a server icon can be several KB, so this also limits upload used by ping floods. |

## flood-block

Blocks a single address that opens far more connections than a real player could.

| Key | Default | Meaning |
|---|---|---|
| `mode` | `during-attack` | `never`, `during-attack` (only while the attack state is elevated or attack) or `always`. |
| `connections` | 30 | Connections from one address within `window-seconds` that trigger a block. Minimum 10. |
| `window-seconds` | 5 | Time window for `connections`. |
| `minutes` | 30 | How long the address stays blocked. |
| `notice-interval-seconds` | 2 | A blocked address is shown the `kick.flood-blocked` message at most once in this interval; other connections from it are closed silently. |
| `allow-verified-players` | false | When true, a player who was verified from the blocked address may still join with that name. Off by default because it lets anyone who verified once from an attacking address keep joining from it. |

Every block has a code like `#SINTESI-7K2QXM`, shown to the player and written in the console. Use `/sintesi blocklist info <code>` to see the address and reason and `/sintesi blocklist remove <code>` to lift it.

Only one address is ever blocked, never a whole subnet. Players who share an address with an attacking machine (some mobile and school networks) are blocked too; staff can lift the block with the code.

## attack

The attack state (normal, elevated, attack) is based on new connections per second across the whole server.

| Key | Default | Meaning |
|---|---|---|
| `window-seconds` | 4 | Seconds averaged to compute the rate. Range 1 to 16. |
| `elevated-rate` | 40 | Connections per second for the elevated state. |
| `attack-rate` | 120 | Connections per second for the attack state. Must be above `elevated-rate`. |
| `cooldown-seconds` | 30 | Calm seconds before the state goes back down. |

During elevated and attack, verification log lines are muted (block lines and state changes are still logged), staff with `sintesi.notify` get alerts, and `captcha: during-attack` turns the captcha on.

## protocol

| Key | Default | Meaning |
|---|---|---|
| `min` | 47 | Lowest accepted protocol number (47 is 1.8). |
| `max` | 777 | Highest accepted protocol number (777 is 26.3). |
| `blocked` | `[]` | Protocol numbers that are refused with `kick.unsupported`, also for verified players (for example `- 47` for 1.8). |
| `required-hostnames` | `[]` | When not empty, only connections that used one of these hostnames to reach the server are accepted (for example `play.example.com`). Leave empty to accept any. |

## verification

| Key | Default | Meaning |
|---|---|---|
| `verified-lifetime-hours` | 72 | How long a verified player (name plus address) can join without verifying again. Counted from the verification, not renewed on join. |
| `timeout-seconds` | 20 | Time a player has to complete verification. |
| `allow-unsupported-protocols` | false | When true, versions Sintesi cannot verify are let through unchecked. When false they are kicked with `kick.unsupported`. |
| `transfer` | true | 1.20.5 and newer clients are moved back to the server automatically after verification. Older clients are asked to reconnect. |
| `max-sessions-per-address` | 1 | Verifications that may run at the same time from one address. |
| `mode` | `always` | `always` verifies every new player. `during-attack` lets new players in without verification while the attack state is normal; they are not stored as verified. Safer is `always`. |
| `captcha-on-fall-failure` | false | When a player's fall does not match (for example a very laggy connection), show the captcha instead of failing; solving it passes. |
| `valid-name-regex` | `[A-Za-z0-9_]{3,16}` | Names that may join. Others are closed at once. |
| `valid-brand-regex` | `[ -~§]+` | Client brands that are accepted (the brand is also limited to 2 to 64 bytes). |
| `transfer-host`, `transfer-port` | `""`, 0 | Where 1.20.5+ clients are sent after verification. Empty and 0 use the address and port the player connected to. |
| `max-online-per-address` | 3 | Players that may be online at the same time from one address; more get `kick.too-many-accounts`. 0 turns it off. 127.0.0.1 is never limited (a proxy or relay on the same machine). |
| `protocol-checks` | true | Extra checks after the first step. Turning them off makes verification weaker. |
| `vehicle-check` | true | An additional check step. Turning it off makes verification weaker. |
| `visible-platform` | false | Shows the ground during verification instead of keeping it invisible. |
| `captcha` | `during-attack` | `never`, `during-attack` or `always`. The player types a short code shown on a map. |
| `captcha-seconds` | 60 | Time to solve the captcha. Range 15 to 300. |
| `captcha-attempts` | 3 | Wrong answers allowed. |
| `physics-tolerance` | 0.0000001 | Internal tolerance. Leave the default unless asked to change it. |
| `compression-threshold` | 256 | Packet size above which verification traffic is compressed. -1 disables compression. |
| `blacklist-threshold` | 2 | Failed verifications from one address that block it. 0 disables the block. |
| `blacklist-minutes` | 10 | How long that block lasts. |

## logging

| Key | Default | Meaning |
|---|---|---|
| `failure-reasons` | false | Logs why each verification failed. Keep it off in production: the reasons tell an attacker what to fix. |
| `player-addresses` | true | Writes player addresses in the log. Set it to false if your privacy rules require it; `/sintesi blocklist info` still shows the address of a block to staff. |

## bedrock

| Key | Default | Meaning |
|---|---|---|
| `exempt-local-connections` | true | Connections created inside the same process (Geyser installed on the proxy) are not checked. |
| `trusted-geyser-addresses` | `[]` | Addresses of standalone Geyser instances. Their connections are not checked. |

## clusters

Notices waves of identical clients even when there is no attack (for example custom bots joining a few at a time) and asks only those clients to solve the captcha. Sintesi learns how often each kind of client normally joins; when one kind suddenly arrives much more often than usual, it must solve the captcha for a while. This also works when `verification.captcha` is `never`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | true | Turns the detection on. |
| `min-sessions` | 15 | Clients of one kind within a minute needed before it can be flagged. |
| `factor` | 5 | How many times above its usual rate a kind must be. |
| `minutes` | 10 | How long a flagged kind must solve the captcha. |

Busy servers that expect many identical clients at once (for example an event start) can raise `min-sessions` or turn it off. What is learned is kept in memory, so right after a restart the usual rates start from zero.

## vpn-block

Refuses logins from VPN, proxy, Tor and hosting provider addresses. Server list pings are never refused. The lists are downloaded by your server from the addresses in `sources`, kept in `vpn-lists/` next to the config and refreshed every `refresh-hours`; no player address is ever sent anywhere.

| Key | Default | Meaning |
|---|---|---|
| `mode` | `during-attack` | `never`, `during-attack` (only while the attack state is elevated or attack) or `always`. With `never` nothing is downloaded. |
| `sources` | three lists | Plain text lists with one address or range per line (`#` starts a comment). The defaults are the X4BNet VPN and datacenter lists for IPv4 and IPv6 (github.com/X4BNet/lists_vpn, no declared license, maintained by their authors) and the Tor exit list from torproject.org. Remove or replace them as you like. |
| `refresh-hours` | 24 | How often the lists are downloaded again. If a download fails the previous copy is kept. |
| `blocked-ranges` | `[]` | Extra addresses or ranges to refuse, for example `203.0.113.0/24`. |
| `allowed-ranges` | `[]` | Addresses or ranges that are never refused even if a list contains them, for example a staff VPN. |

Lists catch datacenters, commercial VPNs and Tor. They do not catch residential proxies (infected or rented home connections), which look like normal players. IPv6 entries are matched per /64. Downloaded lists may not contain ranges wider than /8 (IPv4) or /16 (IPv6), and a list that would cover more than a quarter of all IPv4 addresses is ignored, so a broken or tampered list cannot lock everyone out; your own `blocked-ranges` have no such limit.

## proxy-protocol

Only relevant when the PROXY protocol is enabled on the platform (Velocity `haproxy-protocol`, BungeeCord `proxy_protocol`, Paper `proxies.proxy-protocol`).

| Key | Default | Meaning |
|---|---|---|
| `trusted-addresses` | `[]` | Addresses or ranges (for example `10.0.0.0/8`, `2001:db8::/32`) of the proxies or load balancers allowed to send the PROXY header. Connections from anywhere else are closed. When empty, every connection is trusted and a warning is logged: anyone who can reach the port directly could then pretend to be any address. |

TCPShield: use its PROXY protocol option and list its addresses here. Its older forwarding (player address inside the hostname, `host///address`) is accepted, but Sintesi then sees the TCPShield edge address for every player, so per address limits would apply to the edge; a warning is logged when such a connection arrives.

## metrics

Optional Prometheus endpoint at `/metrics` (connections, logins, pings, verifications, blocked connections, limbo slots, blocklist size, verified players, attack state, gate decisions).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | false | Starts the endpoint. |
| `bind` | `127.0.0.1` | Address to listen on. Keep it on a private address. |
| `port` | 9225 | Port to listen on. |
| `token` | `""` | When set, requests need the header `Authorization: Bearer <token>`. |

## webhook

Sends a message to a Discord channel (or any service that accepts Discord webhooks) when an attack starts and ends. The texts are `webhook.started` and `webhook.ended` in `messages.yml`; mentions are never resolved.

| Key | Default | Meaning |
|---|---|---|
| `url` | `""` | The webhook address (https). Empty turns it off. |
| `username` | `Sintesi` | Name shown on the message. |
| `on-attack-start` | true | Send a message when an attack starts. |
| `on-attack-end` | true | Send a summary when it ends. |

## update-checker

Asks Modrinth for new Sintesi versions. When one is out, the console says so once per version, and staff with `sintesi.update` get a message with a clickable download link a few seconds after they join. `/sintesi version` shows the result and checks again. Only the list of Sintesi versions is downloaded; nothing about your server is sent besides the request itself.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | true | Turns the checker on or off. |
| `interval-hours` | 6 | How often Modrinth is asked (1 to 168). |
| `channel` | `auto` | Which versions count: `release`, `beta` (beta and release) or `alpha` (all). `auto` follows the channel of the version you run, so a beta tells you about newer betas and releases, a release only about newer releases. |

## storage

| Key | Default | Meaning |
|---|---|---|
| `file` | `verified.bin` | File that stores verified players. Names and addresses are stored only as keyed hashes. |
| `type` | `file` | `file` keeps verified players on this proxy only. `mysql` shares them through a MySQL or MariaDB database, so a player verified on one proxy of a network is recognized by all of them. |
| `mysql.host`, `mysql.port`, `mysql.database`, `mysql.username`, `mysql.password` | `127.0.0.1`, 3306, `sintesi`, `sintesi`, `""` | Where the database is. The tables are created automatically. |
| `mysql.table-prefix` | `sintesi_` | Prefix of the two tables (`verified` and `meta`). |
| `mysql.ssl-mode` | `disable` | `disable`, `trust`, `verify-ca` or `verify-full`. Use encryption when the database is not on a private network. |
| `mysql.sync-seconds` | 5 | How often each proxy reads the changes made by the others. |

With `type: mysql`:

- Copy the same `secret.key` to every proxy that uses the database. A proxy with a different key refuses the database (verified players are stored as hashes keyed by it).
- The MariaDB driver (also works with MySQL) is downloaded once from Maven Central into `libs/` and checked against a fixed checksum.
- Every proxy keeps a copy in memory, so logins never wait for the database. A player who is transferred back after verification and lands on another proxy is looked up directly, so they are not verified twice.
- If the database cannot be reached at startup, Sintesi logs it and uses `file` on that proxy until the next restart. If it goes away later, verification keeps working from the copy in memory and changes are written when it comes back (up to 100000 pending writes).

## Usage statistics and privacy

Sintesi sends pseudonymous usage statistics to [FastStats](https://faststats.dev) every 30 minutes: a random server id, plugin, server and Java versions, operating system, CPU core count, player count and online mode. FastStats infers an approximate country and states that it does not store the IP address of your server. Error reports (stack traces and messages of errors inside Sintesi) are sent too; IP addresses in them are replaced before sending. No player names, player addresses or verification data are sent. Nothing is sent on the first start, so you can opt out before the next one.

To opt out, set `enabled=false` in `plugins/faststats/config.properties` (shared by every plugin that uses FastStats), or start the server with `-Dfaststats.enabled=false`. `submitErrors=false` keeps the statistics and turns off only the error reports.

The statistics are stored by FastStats (operated from Germany, data hosted in Frankfurt and Nuremberg, inside the European Union) and are only used to know how many servers run Sintesi, on which platforms and versions, and to find bugs. They do not identify you or your players.

Sintesi itself processes player names and addresses only on your server, to protect it: verified players are stored as keyed hashes (in `verified.bin` or in your own database), blocked addresses are kept in memory, and addresses appear in the log. For that data you are the controller: mention it in the privacy policy of your server. `logging.player-addresses: false` keeps addresses out of the log.

## Commands and permissions

`/sintesi` (alias `/antibot`): `help`, `stats`, `verbose`, `notify`, `reload`, `blocklist add <ip> [minutes]`, `blocklist remove <ip|code>`, `blocklist info <ip|code>`, `blocklist clear`, `blocklist size`, `verified count`, `verified clear`, `verified remove <player> [ip]`, `vpn status`, `vpn check <ip|player>` (an online player name checks the address that player is connected from), `vpn reload`, `version`.

| Permission | Grants |
|---|---|
| `sintesi.admin` | Everything. |
| `sintesi.command` | The base command. |
| `sintesi.command.<subcommand>` | One subcommand, for example `sintesi.command.blocklist`. |
| `sintesi.notify` | Attack alerts. |
| `sintesi.update` | Update notices on join. |
