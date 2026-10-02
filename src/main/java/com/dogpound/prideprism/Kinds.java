package com.dogpound.prideprism;

import java.util.LinkedHashMap;
import java.util.Map;

/** The log's categories -> the action names in each. Shared by the search chips and the graph ball. */
public final class Kinds {
    private Kinds() {}

    public static final Map<String, String[]> ALL = new LinkedHashMap<>();
    static {
        ALL.put("Break", new String[]{"break", "explode", "trample", "burn"});
        ALL.put("Place", new String[]{"place", "fluid", "bucket", "pour", "flow", "ignite"});
        ALL.put("Use", new String[]{"use", "button", "lever", "punch", "interact"});
        ALL.put("Doors", new String[]{"door", "trapdoor", "gate"});
        ALL.put("Chests", new String[]{"item-take", "item-put"});
        ALL.put("Items", new String[]{"drop", "pickup", "craft", "smelt", "brew", "anvil", "use-item", "consume", "item-break"});
        ALL.put("Chat", new String[]{"chat", "command"});
        ALL.put("Combat", new String[]{"attack", "hurt", "hit"});
        ALL.put("Deaths", new String[]{"death", "kill"});
        ALL.put("Money", new String[]{"money", "web-order", "web-reward"});
        ALL.put("Players", new String[]{"join", "leave", "teleport", "respawn", "sleep", "wake", "mount", "dismount", "tame", "breed", "xp", "level", "advancement", "move"});
        ALL.put("Menus", new String[]{"menu-open", "menu-close", "menu-button", "menu-slot", "key"});
    }

    /** which category an action is in ("Other" for anything new) */
    public static String of(String action) {
        for (Map.Entry<String, String[]> e : ALL.entrySet()) for (String a : e.getValue()) if (a.equals(action)) return e.getKey();
        return "Other";
    }
}
