# VelocityBridge

VelocityBridge lets you run **multiple Velocity (Minecraft proxy) servers** at the same time, all sharing the **same backend server farm**.

Traditionally a Minecraft network had to use a single proxy. With VelocityBridge, multiple Velocity proxies can operate concurrently, and each proxy has its own public address. Players pick the closest one (or run `/vb proxies` to compare latency and `/vb transfer` to switch mid-session).

## Highlights

- **Shared backends** — Every proxy forwards to the same Paper/Folia backend servers, so players see the same game world regardless of which proxy they entered through.
- **Global player registry** — All proxies aggregate their online players through a leader proxy; `show-max-players` and `/vb list` reflect the whole network.
- **Cross-proxy chat** — Players on different proxies chat together as one network, with optional LunaChat-style romaji-to-Japanese auto-conversion.
- **Cross-proxy transfer** — `/vb transfer` moves a player from one proxy to another using the Minecraft 1.20.5+ Transfer packet.
- **Proxy latency probe** — `/vb proxies` shows every proxy's address, region, player count, and the latency measured from your current proxy, so players can choose the closest node.
- **No extra infrastructure** — Inter-proxy communication uses a leader-type message hub over plain TCP. No Redis or database required.

## How it works

The proxy-to-proxy links work purely through configuration:

1. All proxies use `player-info-forwarding-mode = "modern"` with the **same** `forwarding.secret`. Backends accept connections from any proxy because they verify the HMAC-SHA256 signature against the same secret.
2. All proxies run with `online-mode = true`, so player UUIDs and skins are consistent network-wide.
3. Backends run with `proxies.velocity.enabled = true` (Paper) and `online-mode = false` in `server.properties`, since authentication happens at the proxy.

On top of this, the VelocityBridge plugin implements the coordination between proxies: global player aggregation, cross-proxy chat, and player transfer, all routed through a **leader** proxy that acts as a message hub.

## Repository layout

| Path | Description |
| --- | --- |
| `plugin/` | The VelocityBridge Velocity plugin (Java 25 / Maven) |
| `DEPLOYMENT.md` | Production deployment guide |

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/vb list` | `velocitybridge.list` | List online players across the whole network |
| `/vb status` | `velocitybridge.status` | Show hub state, node info, follower counts, player counts |
| `/vb transfer <proxyId> [player]` | `velocitybridge.transfer` (`velocitybridge.transfer.others` for other players) | Transfer a player to another proxy |
| `/vb proxies` | `velocitybridge.proxies` | List proxies with address, region, player count, and latency from this node |
| `/vbmode` | `velocitybridge.vbmode` | Toggle romaji-to-Japanese chat conversion on/off |

## Requirements

- Minecraft client **1.20.5+** for proxy-to-proxy transfer (Transfer packet).
- Velocity 4.x and matching Paper/Folia backends.
- Java 25 to build the plugin.
