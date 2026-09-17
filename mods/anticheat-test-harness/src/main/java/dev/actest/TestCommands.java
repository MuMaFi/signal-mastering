package dev.actest;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.actest.module.Modules;
import dev.actest.module.TestModule;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Registriert den Befehl {@code /actest}. */
public final class TestCommands {

    private TestCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("actest");

            root.then(ClientCommands.literal("list").executes(ctx -> {
                for (TestModule module : Modules.all()) {
                    ctx.getSource().sendFeedback(Component.literal(String.format(Locale.ROOT,
                            "%s [%s] %s  (%.2f-%.2f %s, Standard %.2f)",
                            module.isEnabled() ? "an " : "aus",
                            module.id(),
                            module.description(),
                            module.min(), module.max(), module.unit(), module.defaultIntensity())));
                }
                return 1;
            }));

            root.then(ClientCommands.literal("status").executes(ctx -> {
                Minecraft mc = ctx.getSource().getClient();
                ctx.getSource().sendFeedback(Component.literal(
                        "Verbindung: " + TestGate.currentAddress(mc)
                                + " | freigegeben: " + (TestGate.isAllowed(mc) ? "ja" : "nein")));
                ctx.getSource().sendFeedback(Component.literal(
                        "Protokoll: " + TestLog.currentFile()));
                return 1;
            }));

            root.then(ClientCommands.literal("stop").executes(ctx -> {
                AntiCheatTestClient.disableAll(ctx.getSource().getClient());
                return 1;
            }));

            root.then(ClientCommands.literal("allow").executes(ctx -> allowCurrent(ctx.getSource(), true)));
            root.then(ClientCommands.literal("deny").executes(ctx -> allowCurrent(ctx.getSource(), false)));

            for (TestModule module : Modules.all()) {
                root.then(ClientCommands.literal(module.id())
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            Minecraft mc = ctx.getSource().getClient();
                            if (!requireAllowed(ctx.getSource(), mc)) {
                                return 0;
                            }
                            module.setEnabled(true, mc);
                            ctx.getSource().sendFeedback(Component.literal(
                                    module.id() + " an, Intensitaet " + AntiCheatTestClient.trim(module.intensity())));
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            module.setEnabled(false, ctx.getSource().getClient());
                            ctx.getSource().sendFeedback(Component.literal(module.id() + " aus"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("set")
                                .then(ClientCommands.argument("wert", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> {
                                            Minecraft mc = ctx.getSource().getClient();
                                            double value = DoubleArgumentType.getDouble(ctx, "wert");
                                            module.setIntensity(value, mc);
                                            ctx.getSource().sendFeedback(Component.literal(
                                                    module.id() + " Intensitaet "
                                                            + AntiCheatTestClient.trim(module.intensity())
                                                            + " " + module.unit()));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("sweep")
                                .then(ClientCommands.argument("von", DoubleArgumentType.doubleArg())
                                        .then(ClientCommands.argument("bis", DoubleArgumentType.doubleArg())
                                                .then(ClientCommands.argument("schritt", DoubleArgumentType.doubleArg())
                                                        .then(ClientCommands.argument("sekunden", IntegerArgumentType.integer(1, 300))
                                                                .executes(ctx -> {
                                                                    Minecraft mc = ctx.getSource().getClient();
                                                                    if (!requireAllowed(ctx.getSource(), mc)) {
                                                                        return 0;
                                                                    }
                                                                    AntiCheatTestClient.sweep().start(mc, module,
                                                                            DoubleArgumentType.getDouble(ctx, "von"),
                                                                            DoubleArgumentType.getDouble(ctx, "bis"),
                                                                            DoubleArgumentType.getDouble(ctx, "schritt"),
                                                                            IntegerArgumentType.getInteger(ctx, "sekunden"));
                                                                    return 1;
                                                                })))))));
            }

            dispatcher.register(root);
        });
    }

    private static boolean requireAllowed(FabricClientCommandSource source, Minecraft mc) {
        if (TestGate.isAllowed(mc)) {
            return true;
        }
        source.sendError(Component.literal(TestGate.denialReason(mc)));
        return false;
    }

    private static int allowCurrent(FabricClientCommandSource source, boolean allow) {
        Minecraft mc = source.getClient();
        ServerData server = mc.getCurrentServer();
        TestConfig cfg = TestConfig.get();

        if (server == null) {
            cfg.allowSingleplayer = allow;
            cfg.save();
            source.sendFeedback(Component.literal("Einzelspieler " + (allow ? "freigegeben" : "gesperrt")));
            return 1;
        }

        String host = TestConfig.normalise(server.ip);
        cfg.allowedServers.removeIf(entry -> TestConfig.normalise(entry).equals(host));
        if (allow) {
            cfg.allowedServers.add(host);
        }
        cfg.save();
        source.sendFeedback(Component.literal(
                "Server " + host + " " + (allow ? "freigegeben" : "gesperrt")));
        return 1;
    }
}
