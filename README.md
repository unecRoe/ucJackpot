# ucJackpot

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x-brightgreen)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

**ucJackpot** is a free, open-source jackpot plugin for Spigot, Paper, and Purpur servers. It supports money entries, real item entries, ticket entries, multiple jackpot rooms, GUI-driven player flows, mailbox overflow protection, audit logs, PlaceholderAPI, Vault economy support, and configurable localization.

Items deposited into a jackpot stay as real items. They are not converted into money. The winner receives the item pool directly, and overflow rewards are safely moved to the mailbox when the inventory is full.

---

## Highlights

- Money, item, hybrid, ticket, low-risk, high-risk, and event jackpot rooms
- Fully configurable room files under `jackpots/*.yml`
- GUI files separated from chat language files
- Only the selected locale and fallback locale are extracted on startup
- Clear console errors for broken language, GUI, and jackpot YAML files
- Dynamic command aliases from `config.yml`
- Help output adapts to the command the player used, such as `/jackpot rooms` or `/jp rooms`
- Persistent favorite money amount
- Reclaim GUI for deposits before the round countdown starts
- Mailbox system for full inventories and offline item delivery
- Draw history with draw id, seed, hash, entry id, and detailed audit data
- Java 21 build target, compatible with Java 21+ runtimes

---

## Screenshots

Create the screenshots below and place them in `docs/images/`.

### Main Menu

![Main menu screenshot placeholder](docs/images/01-main-menu.png)

Place a screenshot here showing the default jackpot main GUI with the active room, money entry, item entry, deposits, rooms, preview, fairness, season, stats, history, ticket, and reclaim buttons.

### Rooms Menu

![Rooms menu screenshot placeholder](docs/images/02-rooms-menu.png)

Place a screenshot here showing the centered room layout with General Room, Low Roller, High Roller, Item Room, and Event Room.

### Money Deposit

![Money deposit screenshot placeholder](docs/images/03-money-deposit.png)

Place a screenshot here showing quick money amounts, manual amount entry, and favorite amount display.

### Item Selection

![Item selection screenshot placeholder](docs/images/04-item-selection.png)

Place a screenshot here showing accepted item selection, multi-item selection, and the confirm button.

### Deposits Viewer

![Deposits viewer screenshot placeholder](docs/images/05-deposits-viewer.png)

Place a screenshot here showing the GUI where players can see who joined the active jackpot and inspect each player's money, item, and ticket deposits.

### Winner Watch Animation

![Watch animation screenshot placeholder](docs/images/06-watch-animation.png)

Place a screenshot here showing the watch mode animation while entries are rotating.

### History and Fairness

![History and fairness screenshot placeholder](docs/images/07-history-fairness.png)

Place a screenshot here showing draw history, seed/hash data, or the fairness panel.

---

## Requirements

| Requirement | Status |
| --- | --- |
| Java | Build target Java 21, runs on Java 21+ |
| Server | Spigot, Paper, or Purpur 1.21.x |
| Economy | Vault + economy plugin for money rooms |
| PlaceholderAPI | Optional |
| Database | SQLite by default, MySQL/MariaDB supported |

---

## Installation

1. Download `ucJackpot-1.0.0.jar`.
2. Put it into your server `plugins/` folder.
3. Start the server once.
4. Edit `plugins/ucJackpot/config.yml`.
5. Edit jackpot rooms under `plugins/ucJackpot/jackpots/`.
6. Run `/ucjackpot reload`.

Default player commands are available through:

- `/ucjackpot`
- `/jackpot`
- `/jp`
- `/ucj`

Aliases are configurable in `config.yml`.

---

## Configuration Layout

| File | Purpose |
| --- | --- |
| `config.yml` | Global settings, storage, economy, metrics, logging, command aliases, security |
| `jackpots/*.yml` | Jackpot room rules, limits, values, rewards, tickets, season id |
| `lang/<locale>.yml` | Chat feedback only |
| `gui/<locale>/<menu>.yml` | GUI titles, item names, lore, slots, materials, sounds |

Default language is English. Supported locales:

`en`, `tr`, `de`, `fr`, `es`, `pt`, `ru`, `ar`, `zh`, `ja`, `ko`

The plugin extracts only the configured locale and fallback locale. Changing `settings.default-locale` and running `/ucjackpot reload` loads the new language files automatically.

---

## Commands

See [docs/COMMANDS.md](docs/COMMANDS.md) for the complete command and permission list.

Common commands:

| Command | Description |
| --- | --- |
| `/jackpot rooms` | Open the room selector |
| `/jackpot open [room]` | Open a room's main GUI |
| `/jackpot join [room] [amount]` | Enter with money |
| `/jackpot item [room]` | Enter with the held item |
| `/jackpot ticket [room]` | Enter with a jackpot ticket |
| `/jackpot mailbox` | Claim mailbox rewards |
| `/jackpot history` | Open recent winners |
| `/jackpot reload` | Reload config, language, GUI, and rooms |

---

## Placeholders

PlaceholderAPI identifier: `ucjackpot`

See [docs/PLACEHOLDERS.md](docs/PLACEHOLDERS.md) for all available placeholders.

Example:

```text
%ucjackpot_pot%
%ucjackpot_players%
%ucjackpot_time_left%
%ucjackpot_player_chance%
```

## Building From Source

```bash
mvn clean package
```

The compiled jar is generated at:

```text
target/ucJackpot-1.0.0.jar
```

This project is built with Java 21 release bytecode. You can compile it with JDK 21 or newer.

## License

ucJackpot is released under the MIT License. See [LICENSE](LICENSE).
