package dev.riftwalk.interfaces;

import java.awt.*;

public interface IGlowingEntity {
    Color riftwalk$glowColor();

    void riftwalk$glowColor(Color color);

    boolean riftwalk$isGlowing();

    void riftwalk$isGlowing(boolean value);
}