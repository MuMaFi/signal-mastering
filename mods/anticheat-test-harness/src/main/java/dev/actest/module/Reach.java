package dev.actest.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Greift Ziele ausserhalb der normalen Reichweite an.
 *
 * <p>Die Intensitaet ist die Gesamtreichweite in Bloecken. Vanilla liegt bei rund
 * drei Bloecken; jeder Wert darueber sollte serverseitig auffallen. Angegriffen
 * wird nur, was tatsaechlich im Blickstrahl liegt, damit der Test dem normalen
 * Kampfverhalten entspricht und keine Treffer erzeugt, die niemand anvisiert hat.
 */
public final class Reach extends TestModule {

    private static final int COOLDOWN_TICKS = 10;

    private int cooldown;

    public Reach() {
        super("reach", "Angriffsreichweite vergroessern", 3.0, 8.0, 5.0, "Bloecke");
    }

    @Override
    public void tick(Minecraft mc, LocalPlayer player) {
        if (!mc.options.keyAttack.isDown()) {
            cooldown = 0;
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        Entity target = findTarget(mc, player, intensity());
        if (target == null) {
            return;
        }
        mc.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        cooldown = COOLDOWN_TICKS;
    }

    @Override
    public void onDisable(Minecraft mc) {
        cooldown = 0;
    }

    /** Naechstes Lebewesen im Blickstrahl innerhalb der angegebenen Reichweite. */
    static Entity findTarget(Minecraft mc, LocalPlayer player, double range) {
        if (mc.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(range));
        AABB search = player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);

        List<Entity> candidates = mc.level.getEntities(player, search,
                entity -> entity.isAlive() && entity.isPickable());

        Entity best = null;
        double bestDistance = range * range;
        for (Entity candidate : candidates) {
            AABB box = candidate.getBoundingBox().inflate(candidate.getPickRadius());
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = eye.distanceToSqr(hit.get());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
