package dev.marceline.interfaces;

import java.awt.*;

public interface IGlowingEntity {
    Color marceline$glowColor();

    void marceline$glowColor(Color color);

    boolean marceline$isGlowing();

    void marceline$isGlowing(boolean value);
}