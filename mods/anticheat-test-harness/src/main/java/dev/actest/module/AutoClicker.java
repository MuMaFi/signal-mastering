package dev.actest.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

/**
 * Loest Angriffe in einer festen Rate aus.
 *
 * <p>Die Intensitaet ist die Rate in Klicks pro Sekunde. Menschliche Spieler
 * liegen selten dauerhaft ueber etwa 15; gleichmaessige hohe Raten sind das
 * Muster, auf das eine Klickanalyse ansprechen sollte.
 */
public final class AutoClicker extends TestModule {

    private static final double TICKS_PER_SECOND = 20.0;

    private double accumulator;

    public AutoClicker() {
        super("autoclick", "Angriffe automatisch ausloesen", 1.0, 30.0, 12.0, "Klicks/s");
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        if (!mc.options.keyAttack.isDown()) {
            accumulator = 0.0;
            return;
        }
        accumulator += intensity() / TICKS_PER_SECOND;
        while (accumulator >= 1.0) {
            accumulator -= 1.0;
            click(mc, player);
        }
    }

    @Override
    public void onDisable(Minecraft mc) {
        accumulator = 0.0;
    }

    private void click(Minecraft mc, LocalPlayer player) {
        Entity target = Reach.findTarget(mc, player, 3.0);
        if (target != null) {
            mc.gameMode.attack(player, target);
        }
        player.swing(InteractionHand.MAIN_HAND);
    }
}
