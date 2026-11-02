package xyz.yourboykyle.secretroutes.config;

import net.minecraft.network.chat.Component;

/** Not based on distance this time like the rest :D */
public enum BossRouteMode {
    ADG("ADG", "f7boss"),
    CLASSIC("Classic roles", "f7boss:1");

    private final String label;
    private final String routeKey;

    BossRouteMode(String label, String routeKey) {
        this.label = label;
        this.routeKey = routeKey;
    }

    public String label() {
        return label;
    }

    public String routeKey() {
        return routeKey;
    }
    public Component getDisplayName() {
        return Component.literal(label);
    }
}
