package dev.actest.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Daempft den Rueckstoss nach einem Treffer.
 *
 * <p>Intensitaet 1.0 hebt ihn vollstaendig auf, 0.4 nimmt 40 Prozent weg. Der
 * Server erwartet nach dem Rueckstoss eine bestimmte Bewegung; bleibt sie aus,
 * ist das die Abweichung, auf die ein Anti-Cheat reagieren sollte. Interessant
 * ist der Wert, ab dem er es tut.
 */
public final class AntiKnockback extends TestModule {

    public AntiKnockback() {
        super("antikb", "Rueckstoss nach Treffern daempfen", 0.0, 1.0, 1.0, "Anteil");
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        // hurtTime laeuft nach einem Treffer von 10 herunter und deckt das
        // Rueckstossfenster ab.
        if (player.hurtTime <= 0) {
            return;
        }
        double keep = 1.0 - intensity();
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x * keep, motion.y, motion.z * keep);
    }
}
