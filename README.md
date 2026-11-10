# JESViewDistance

Gives LuckPerms groups their own view and simulation distance on Paper 26.2+.

Players get the first group from `config.yml` they're in. Everyone else uses `server.properties`. Changes apply right away when someone's rank changes, no rejoin needed.

## Setup

1. Install [LuckPerms](https://luckperms.net) and drop the jar into `plugins/` on each backend server (not the proxy).
2. Set the default player distances in `server.properties`.
3. Add your ranks to `plugins/JESViewDistance/config.yml` and run `/vd reload`.

```yaml
groups:
  premium:
    view-distance: 12
    simulation-distance: 5
```

## Commands

Alias `/vd`, permission `jesviewdistance.admin` (op by default).

| Command | What it does |
| --- | --- |
| `/viewdistance check <player>` | Shows their current distances, client render distance and group |
| `/viewdistance set <player> <view> <simulation>` | Temporary override for testing |
| `/viewdistance reset <player>` | Back to their group's distances |
| `/viewdistance reload` | Reloads the config and applies it to everyone online |

Players never see further than their own render distance setting.

## Building

```
./gradlew build
```

The jar ends up in `build/libs`.
