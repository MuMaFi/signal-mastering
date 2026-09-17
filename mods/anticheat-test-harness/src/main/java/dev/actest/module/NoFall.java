package dev.actest.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Meldet dem Server waehrend des Falls Bodenkontakt.
 *
 * <p>Damit bleibt der Fallschaden aus. Die Intensitaet legt fest, ab welcher
 * Fallgeschwindigkeit die Meldung einsetzt: kleine Werte greifen frueh und sind
 * auffaellig, grosse Werte nur bei tiefen Stuerzen.
 */
public final class NoFall extends TestModule {

    public NoFall() {
        super("nofall", "Bodenkontakt waehrend des Falls melden", 0.0, 2.0, 0.5, "Fallrate");
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        if (player.onGround()) {
            return;
        }
        double falling = -player.getDeltaMovement().y;
        if (falling < intensity()) {
            return;
        }
        player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision));
    }
}
