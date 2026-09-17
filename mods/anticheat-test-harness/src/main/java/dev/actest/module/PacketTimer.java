package dev.actest.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Sendet zusaetzliche Bewegungspakete pro Tick.
 *
 * <p>Das entspricht dem serverseitig sichtbaren Teil eines Timer-Hacks: Der Client
 * meldet sich oefter als die 20 Mal pro Sekunde, die der Server erwartet. Die
 * Intensitaet ist die Anzahl zusaetzlicher Pakete je Tick.
 */
public final class PacketTimer extends TestModule {

    public PacketTimer() {
        super("timer", "Zusaetzliche Bewegungspakete senden", 0.0, 10.0, 2.0, "Pakete/Tick");
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        int extra = (int) Math.round(intensity());
        for (int i = 0; i < extra; i++) {
            player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                    player.position(),
                    player.getYRot(),
                    player.getXRot(),
                    player.onGround(),
                    player.horizontalCollision));
        }
    }
}
