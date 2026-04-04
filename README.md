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
- Toggles modules on and off automatically based on triggers and timers
    - Active HUD: shows number of modules pending timer re-enable
