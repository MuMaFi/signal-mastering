package dev.actest.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Sendet den Abbaufortschritt mehrfach pro Tick.
 *
 * <p>Die Intensitaet ist die Anzahl zusaetzlicher Durchlaeufe. Dadurch bricht ein
 * Block deutlich schneller als es das Werkzeug erlaubt, was eine serverseitige
 * Abbauzeitpruefung erkennen sollte.
 */
public final class FastBreak extends TestModule {

    public FastBreak() {
        super("fastbreak", "Bloecke schneller abbauen", 1.0, 10.0, 3.0, "Durchlaeufe");
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        if (!mc.options.keyAttack.isDown()) {
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }
        int repeats = (int) Math.round(intensity());
        for (int i = 0; i < repeats; i++) {
            mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection());
        }
    }
}
