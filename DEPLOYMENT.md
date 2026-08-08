# VelocityBridge Production Deployment Guide

This guide describes how to deploy a VelocityBridge network in production: multiple Velocity proxies sharing one backend server farm, coordinated through a leader proxy.

## 1. Reference architecture

```
  server list / guide page publishes one address per proxy
  ├── jp.play.example.com ──► Proxy A (Japan / Tokyo)      ──┐
  ├── us.play.example.com ──► Proxy B (US East / New York) ──┼── players pick the
  └── eu.play.example.com ──► Proxy C (EU / Frankfurt)     ──┘   closest address

              Proxy A ──┐   (leader hub, TCP)   ┌── Proxy B
                        └──────────┬──────────┘
                                   ▼
                            ┌──────────┐
                            │ Leader   │   one proxy doubles
                            │ Velocity │   as the message hub
                            └────┬─────┘
                                 │   modern forwarding (shared secret)
                      ┌──────────┴──────────┐
                      ▼                     ▼
                ┌──────────┐         ┌──────────┐
                │ Backend  │  ...    │ Backend  │    shared Paper/Folia farm
                │ (lobby)  │         │ (factions)│   (firewalled to proxy IPs)
                └──────────┘         └──────────┘
```

- **No GeoDNS.** Each proxy has its own public address (plain A/AAAA record); players choose the closest one themselves.
- Players can compare nodes in-game with `/vb proxies` (address, region, player count, latency from their current proxy) and switch mid-session with `/vb transfer <proxyId>` (client must be 1.20.5+).
- All proxies share the **same backend farm** and the **same `forwarding.secret`**.
- One proxy runs as the **leader** and relays inter-proxy messages (global player list, chat, transfers).

## 2. Prerequisites

- Minecraft client **1.20.5+** to use `/vb transfer` (Transfer packet).
- Velocity 4.x proxy binary, Paper/Folia 1.20.5+ backends.
- Java 25 to build the plugin (`mvn package` → `target/velocitybridge.jar`).
- A permission plugin on the proxies (e.g. LuckPerms) if you want to manage command permissions.

## 3. Step-by-step setup

### 3.1 Build the plugin

```bash
cd plugin
mvn package
# produces plugin/target/velocitybridge.jar
```

### 3.2 Shared forwarding secret

Generate **one** secret and reuse it everywhere (proxies and backends).

```bash
openssl rand -base64 32   # example; any long random string works
```

Distribute it securely to every node (secrets management / config management tooling; never commit it to git).

### 3.3 Backend servers (Paper/Folia)

In each backend:

- `config/paper-global.yml`:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: '<shared-forwarding-secret>'
```

- `server.properties`: `online-mode=false` (auth happens at the proxy).
- Firewall: only allow inbound connections from the proxies' IPs on the backend port.

> Note: modern forwarding (`velocity` section) must be enabled for Velocity's modern forwarding to work. Do not enable BungeeCord forwarding at the same time.

### 3.4 Proxies

**Leader proxy** — `velocity.toml`:

```toml
online-mode = true
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"   # the shared secret
accepts-transfers = true                        # required for /vb transfer
```

Plugin config `plugins/velocitybridge/velocitybridge.conf`:

```yaml
node-id: "proxy-1"
mode: "leader"
leader-address: ""
hub-port: 51850

proxies:
  - id: "proxy-1"
    address: "jp.play.example.com:25565"
    region: "Japan (Tokyo)"
  - id: "proxy-2"
    address: "us.play.example.com:25566"
    region: "US East (New York)"
  - id: "proxy-3"
    address: "eu.play.example.com:25567"
    region: "EU (Frankfurt)"
```

**Follower proxies** — `velocity.toml` identical except bind address; `accepts-transfers = true` on every proxy. Plugin config:

```yaml
node-id: "proxy-2"
mode: "follower"
leader-address: "proxy1.example.com:51850"   # reachable leader hub
hub-port: 51850

# the SAME proxies list as on the leader
proxies:
  - id: "proxy-1"
    address: "jp.play.example.com:25565"
    region: "Japan (Tokyo)"
  - id: "proxy-2"
    address: "us.play.example.com:25566"
    region: "US East (New York)"
  - id: "proxy-3"
    address: "eu.play.example.com:25567"
    region: "EU (Frankfurt)"
```

> The `proxies` list is used by `/vb transfer` to resolve target addresses and by `/vb proxies` for display, so **all nodes must share the same list**.

> **Important for `/vb transfer`:** each `address` must be resolvable by the player's *client* (not just by the proxies). Use public hostnames or public IPs, never private IPs — the address is sent to the client in the Transfer packet and the client connects to it directly.

> The plugin reads the hub secret from `forwarding.secret` in its data directory. If missing, it generates a random one — that would **break inter-proxy auth**, so make sure the same secret file is present on every node (the same content as `forwarding-secret-file`).

### 3.5 Publishing the proxy list

Because players choose their own proxy, publish the addresses somewhere visible:

- A guide / website page listing each address and its region.
- A MOTD per proxy advertising its name and region (e.g. `proxy-1` → "Japan (Tokyo)").
- In-game, players can run `/vb proxies` to see every node's address, region, player count, and the latency measured from the node they are currently connected to.

Latency is measured **proxy-to-proxy** (a Minecraft status ping from the current node to each other node), so it approximates "how much extra latency you would add by transferring to proxy X". For the most accurate per-player value, players should compare the ping column in their in-game multiplayer server list for each address.

### 3.6 Start order

1. Start all backends.
2. Start the **leader** proxy (it owns the hub).
3. Start the follower proxies (they auto-reconnect with exponential backoff, so order after the leader does not matter).

Verify with `/vb status` on each proxy: followers should report `Hub connected: true`, and the leader should list the connected nodes.

## 4. Permissions

Grant via LuckPerms (or equivalent):

| Permission | Default | Purpose |
| --- | --- | --- |
| `velocitybridge.list` | all players | `/vb list` |
| `velocitybridge.status` | all players | `/vb status` |
| `velocitybridge.proxies` | all players | `/vb proxies` |
| `velocitybridge.vbmode` | all players | `/vbmode` |
| `velocitybridge.transfer` | admins | `/vb transfer <proxyId>` (self) |
| `velocitybridge.transfer.others` | admins | `/vb transfer <proxyId> <player>` |

## 5. Security hardening

- **Firewall the backends**: only the proxies' IPs may connect to backend ports.
- **Keep the hub private**: the leader hub port (e.g. 51850) should be reachable only by the follower proxies (firewall / private network). Inter-proxy traffic is authenticated with HMAC-SHA256 over the shared secret, but it is **not encrypted**.
- **Encrypt hub traffic when crossing the internet** (multi-region setups):
  - Current implementation ships plain TCP only. If proxies span regions, terminate TLS in front of the hub port (e.g. a reverse proxy / tunnel such as stunnel, WireGuard, or a VPN mesh) so hub traffic cannot be eavesdropped on. The shared-secret auth alone does not provide confidentiality.
- **Never log or commit** the `forwarding.secret`.

## 6. Availability and failure handling

- **Leader failure (SPOF)**: inter-proxy features (global chat, transfer) pause until the leader recovers or is replaced. Each proxy keeps serving its own players independently.
  - **Recovery**: start a new leader (or promote a follower) by changing `mode` and `leader-address` in the configs and restarting, then point all followers at it.
  - **Planned**: automatic leader election — not yet implemented.
- **Follower failure**: its players are removed from the global list after the heartbeat timeout (30 s); the rest of the network is unaffected.
- **Startup**: the leader must be running before followers can synchronize; followers will keep retrying in the background.

## 7. Operational checks

- `/vb status` on every node — confirms hub connectivity, node role, follower count, and player counts.
- `/vb list` — confirms the global player registry.
- `/vb proxies` — confirms every node is reachable and shows per-node latency/region from the current node.
- Cross-proxy chat: a player on proxy A sends a message; players on proxy B should see it.
- Transfer smoke test: `/vb transfer proxy-2` from proxy-1; the client should rejoin through proxy-2 with the same UUID/skin.

## 8. Migration / upgrade checklist

- Keep all proxies on the **same** plugin version.
- Keep the `forwarding.secret` and `proxies` list identical across nodes when upgrading config.
- Upgrade followers first, then the leader; or schedule a maintenance window (leader restart interrupts inter-proxy features).
- After upgrade, verify `/vb status` and a cross-proxy chat/transfer.

## 9. Known limitations

- `/vb reload` is not implemented; restart the proxy to apply config changes.
- Hub traffic is not TLS-encrypted out of the box.
- No automatic leader election; leader is a manual SPOF.
- Duplicate logins across proxies follow the backend default (last connection wins).
- Transfer requires clients on 1.20.5+; older clients cannot use `/vb transfer`.
- `/vb proxies` latency is measured proxy-to-proxy (from the node the player is on), so it is a heuristic — the in-game server list ping column remains the most accurate per-player measure.
