# hxgn Utilities
### A meteor addon

It bundles a few features

### Dependencies
- [Meteor Client](https://meteorclient.com)
- [Baritone](https://github.com/cabaletta/baritone) (required)

Clever Mend   
- Equips low-durability gear to automatically repair everything in your inventory
    - Optionally puts the second most damaged mending item in your offhand to repair two at once
    - Shulker Refill: when your supply of damaged mending items runs low, places a shulker from your inventory, transfers items, breaks it, and picks it back up
    - Restore Inventory: returns any items taken from shulkers back to a shulker when the session ends
    - Active HUD: shows damaged mending item count and current refill state when active

Auto Elytra Replace  
- Exactly what it sounds like. Just a basic elytra replacing module  
    - If you try to fly with an elytra below threshold, without a replacement elytra, it will unequip the elytra to remind you it's about to break.
    - Active HUD: shows elytra count in inventory

Future Totem  
- Basically Future's AutoTotem.
    - Works good
    - Active HUD: shows totem count in inventory

Auto Toggle
- Toggles modules on and off automatically based on a customizable set of conditional _rules_
  - You can create as many _rules_ as you want. Each _rule_ contains a:
    - Trigger (module **activation**, **login**, **attacking**, **elytra**, **sprint**, **block break/place**, **death**, **health**, **hunger**, **Y coordinate**, **dimension change**, **chat contains**) and an
    - Action (**enable** or **disable** a module _permanently_ or _temporarily_, with optional auto-revert)
  - **Chat contains** trigger options:
    - Match entire message — fires only when the message text exactly equals the keyword
    - Match player name only — fires when the sender's name contains the keyword
    - Include whispers — also matches incoming whispers (`player whispers to you: ...`)
    - Reply with whisper — sends the auto-response as a whisper back to the sender (only fires on whisper matches)
    - Auto-response with per-rule cooldown
- Smart Totem trigger (fall height prediction & health boundary)

# Known issues
- Label texts in the 'Rules' screen for AutoToggle can sometimes disappear. Restarting your game fixes it. Will investigate.
