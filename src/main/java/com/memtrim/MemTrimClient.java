package com.memtrim;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.screen.GameMenuScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a garbage collection at moments when nobody is playing, so leftover
 * data (old world, loading garbage) is released instead of piling up.
 */
public class MemTrimClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("memtrim");

    private static final long PAUSE_INTERVAL_MS = 5 * 60 * 1000L; // at most once per 5 min from pause menu
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile long lastCleanup = 0L;

    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        // 1) Right after the game finishes loading (title screen, loading garbage is everywhere)
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> trim("startup", 0));

        // 2) After leaving a world / server (wait a moment so the old world is unloaded first)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> trim("left world", 3000));

        // 3) While the pause menu is open, at most once every 5 minutes
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter < 20) return; // check once per second
            tickCounter = 0;
            if (client.currentScreen instanceof GameMenuScreen
                    && System.currentTimeMillis() - lastCleanup > PAUSE_INTERVAL_MS) {
                trim("pause menu", 0);
            }
        });
    }

    private static void trim(String reason, long delayMs) {
        if (!RUNNING.compareAndSet(false, true)) return;
        lastCleanup = System.currentTimeMillis();

        Thread thread = new Thread(() -> {
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                Runtime rt = Runtime.getRuntime();
                long before = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                System.gc();
                long after = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                LOGGER.info("[MemTrim] {}: {} MB -> {} MB used (freed {} MB)", reason, before, after, before - after);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                RUNNING.set(false);
            }
        }, "MemTrim-GC");
        thread.setDaemon(true);
        thread.start();
    }
}
