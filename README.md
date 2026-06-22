![JukeBox](https://cdn.modrinth.com/data/cached_images/3aa94808854b8eaf8bed3e7b4da6dbb68c71b0ef.gif)
# OwnWorld+

Tired of griefers ruining your hard work? Want a dedicated space to build a massive empire with your friends without any interruptions? OwnWorld+ gives every player their own unique, private territory on a massive grid to build, manage, and expand their world.

---

## Commands & Permissions

Here are the primary player commands included in the plugin:

| Command | Description |
| :--- | :--- |
| `/ownworld` | Opens the graphical Slot Machine World Browser GUI. |
| `/ownworld help` | Shows the in-game command reference menu. |
| `/ownworld create <name>` | Generates your custom private territory. |
| `/ownworld join <name>` | Teleports directly to your territory or one you are invited to. |
| `/ownworld back` | Safely returns you to your last location outside of the private dimensions. |
| `/ownworld invite <player>` | Grants building, breaking, and interaction rights to a friend. |
| `/ownworld remove <player>` | Revokes building and interaction rights from a friend. |
| `/ownworld delete <name>` | Permanently wipes your territory and resets the grid space (Creator only). |

---

## Administration & Tech Details
* **Performance-First Design:** World mapping functions utilize mathematical $O(1)$ grid approximations instead of heavy iteration loops to prevent server-wide TPS drops.
* **Thread Safety:** High-priority event monitoring ensures world permissions respect existing base-protection layers and prevents block-duplication mechanics.

[![CurseForge](https://cdn.modrinth.com/data/cached_images/7a237a04c282652562f92c9f6404821a62a1f291.png)](https://www.curseforge.com/minecraft/bukkit-plugins/ownworld) [![Modrinth](https://cdn.modrinth.com/data/cached_images/b8d2e2943380a7a81162171b73bb2e5d111f2039.png)](https://modrinth.com/plugin/ownworld)
