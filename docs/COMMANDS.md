# Commands and Permissions

The primary command is `/ucjackpot`.

Default aliases from `config.yml`:

- `/jackpot`
- `/jp`
- `/ucj`

Help output uses the label the player typed. For example, `/jp` shows `/jp rooms`, while `/jackpot` shows `/jackpot rooms`.

## Player Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/ucjackpot` | `ucjackpot.use` | Show command help |
| `/ucjackpot rooms` | `ucjackpot.use` | Open the room selector |
| `/ucjackpot open [room]` | `ucjackpot.use` | Open the main GUI for a room |
| `/ucjackpot join [room] [amount]` | `ucjackpot.join.money` | Enter with money |
| `/ucjackpot item [room]` | `ucjackpot.join.item` | Enter with the held item |
| `/ucjackpot ticket [room]` | `ucjackpot.join.ticket` | Enter with a valid ticket |
| `/ucjackpot stats` | `ucjackpot.stats` | Open personal statistics |
| `/ucjackpot top` | `ucjackpot.top` | Show leaderboard |
| `/ucjackpot history` | `ucjackpot.history` | Show recent winners |
| `/ucjackpot mailbox` | `ucjackpot.mailbox` | Claim queued item rewards |
| `/ucjackpot title` | `ucjackpot.use` | Toggle personal draw title notifications |
| `/ucjackpot season [top] [season]` | `ucjackpot.season` | Show season leaderboard |

## Admin Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/ucjackpot reload` | `ucjackpot.reload` | Reload config, language, GUI, rooms, and aliases |
| `/ucjackpot start [room]` | `ucjackpot.start` | Start a room |
| `/ucjackpot stop [room]` | `ucjackpot.stop` | Stop a room |
| `/ucjackpot cancel [room]` | `ucjackpot.cancel` | Cancel a room and refund active deposits |
| `/ucjackpot draw [room]` | `ucjackpot.draw` | Force a draw |
| `/ucjackpot ticket give <player> <amount> [room]` | `ucjackpot.ticket.give` | Give jackpot tickets |
| `/ucjackpot season reward [season] [limit]` | `ucjackpot.season.reward` | Run season reward commands |
| `/ucjackpot selftest` | `ucjackpot.selftest` | Validate runtime state |

`ucjackpot.admin` grants access to admin commands.

## Extra Permissions

| Permission | Description |
| --- | --- |
| `ucjackpot.notify` | Receive admin notifications |
| `ucjackpot.bypass.cooldown` | Bypass entry cooldown |
| `ucjackpot.bypass.limit` | Bypass entry limit |
