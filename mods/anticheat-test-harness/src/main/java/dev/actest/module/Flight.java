package dev.actest.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

/**
 * Setzt clientseitig die Flugfreigabe und haelt die Hoehe.
 *
 * <p>Das ist der klassische Fall, den jeder Anti-Cheat abfangen muss: Der Client
 * behauptet zu fliegen, ohne dass der Server das je erlaubt hat. Die Intensitaet
 * steuert die Steiggeschwindigkeit; 0 bedeutet schweben auf der Stelle.
 */
public final class Flight extends TestModule {

    private boolean savedFlying;
    private boolean savedMayFly;

    public Flight() {
        super("fly", "Clientseitiges Fliegen", 0.0, 1.0, 0.0, "Steigen");
    }

    @Override
    public void onEnable(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player != null) {
            Abilities abilities = player.getAbilities();
            savedFlying = abilities.flying;
            savedMayFly = abilities.mayfly;
        }
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        Abilities abilities = player.getAbilities();
        abilities.mayfly = true;
        abilities.flying = true;

        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x, intensity(), motion.z);
    }

    @Override
    public void onDisable(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player != null) {
            Abilities abilities = player.getAbilities();
            abilities.flying = savedFlying;
            abilities.mayfly = savedMayFly;
        }
    }
}
