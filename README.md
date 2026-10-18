# KitBot (standalone Fabric mod, Minecraft 26.2)

Chat-triggered kit bot for TPA anarchy servers. Needs only Fabric Loader + Fabric API. **No Meteor Client, no Baritone.**

## What it does
1. A player types `!1kit pvp` (also `totems`, `obsidian`, `random`) in chat.
2. The bot walks to that kit's chest, opens it and takes the **first item stack** (put one shulker box per slot).
3. It sends `/tpa <player>`. When the player accepts and is next to the bot, it drops the kit.
4. 5 seconds later it runs `/kill`, respawns at its bed beside the stash, and takes the next request.
5. Each player has a 1 hour cooldown (set when the kit is delivered).
If the TPA isn't accepted in time, the bot puts the kit back in the chest.

## Setup in game
Commands are typed in chat and handled locally (never sent to the server):

| Command | Does |
|---|---|
| `.kb on` / `.kb off` | start / stop the bot |
| `.kb mode region` | oak button: left click = corner 1, right click = corner 2 |
| `.kb mode pvp` (or `totems`, `obsidian`, `random`) | oak button: click a chest to bind it to that kit |
| `.kb mode off` | oak button works normally again |
| `.kb status` | show state, region, bound chests |
| `.kb clear` | forget region and bound chests |
| `.kb resetcd <player>` | reset a player's cooldown |
| `.kb reload` | reload `config/kitbot.json` |

1. Put the kits in chests. Put a sign on the front of each chest: `Pvp`, `Totems`, `Obsidian`, `Random`.
2. `.kb mode region`, hold an oak button, select two corners around the stash (particles outline it while you hold the button).
3. Optionally bind chests directly with `.kb mode pvp` etc. Bound chests override sign scanning.
4. Make sure the bot's spawn is set by a bed in the stash and its inventory is empty (the oak button is ignored).
5. `.kb mode off`, then `.kb on`.

Settings (prefix, chat regex, cooldown, kill delay, TPA command...) are in `.minecraft/config/kitbot.json`.
If requests are not picked up, change `chatRegex` to match the server's chat format (group 1 = name, group 2 = message).

## Limits
- Walking is a simple straight line to the chest (jumps over 1-block steps). Keep the path inside the stash clear of doors, stairs and holes.
- The bot should not be used where bots or `/kill` loops are against the server's rules.

## Building
Push this repo to GitHub: the included Action builds it, and the jar is under Actions -> latest run -> Artifacts (`kitbot-*.jar`, not the `-sources` one).
Locally: install JDK 25 and Gradle 9.5.1, then `gradle build` (jar in `build/libs`).
Versions are in `gradle.properties`; check https://fabricmc.net/develop/ if they have moved on.
