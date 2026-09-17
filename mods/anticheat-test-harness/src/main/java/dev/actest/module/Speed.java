package dev.actest.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Erhoeht die waagerechte Bewegungsgeschwindigkeit um einen Faktor.
 *
 * <p>1.0 entspricht normalem Gehen. Der Faktor laesst sich schrittweise anheben,
 * um die Toleranzschwelle der Bewegungspruefung zu finden.
 */
public final class Speed extends TestModule {

    private static final double MOVING_THRESHOLD = 0.01;

    public Speed() {
        super("speed", "Bewegung waagerecht beschleunigen", 1.0, 5.0, 1.5, "Faktor");
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        if (Math.abs(motion.x) < MOVING_THRESHOLD && Math.abs(motion.z) < MOVING_THRESHOLD) {
            return;
        }
        double factor = intensity();
        player.setDeltaMovement(motion.x * factor, motion.y, motion.z * factor);
    }
}
