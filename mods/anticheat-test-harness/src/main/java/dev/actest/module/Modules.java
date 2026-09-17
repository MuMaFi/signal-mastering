package dev.actest.module;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;

/** Registrierung aller Testmodule. */
public final class Modules {

    private static final Map<String, TestModule> REGISTRY = new LinkedHashMap<>();

    public static final AntiKnockback ANTI_KNOCKBACK = register(new AntiKnockback());
    public static final Speed SPEED = register(new Speed());
    public static final Flight FLIGHT = register(new Flight());
    public static final NoFall NO_FALL = register(new NoFall());
    public static final Reach REACH = register(new Reach());
    public static final FastBreak FAST_BREAK = register(new FastBreak());
    public static final AutoClicker AUTO_CLICKER = register(new AutoClicker());
    public static final PacketTimer PACKET_TIMER = register(new PacketTimer());

    private Modules() {
    }

    private static <T extends TestModule> T register(T module) {
        REGISTRY.put(module.id(), module);
        return module;
    }

    public static Collection<TestModule> all() {
        return REGISTRY.values();
    }

    public static TestModule byId(String id) {
        return REGISTRY.get(id);
    }
}
