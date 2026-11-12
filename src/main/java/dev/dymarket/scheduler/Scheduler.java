package dev.dymarket.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;

/**
 * One place for scheduling. Folia runs work on the region or entity that owns it;
 * other servers use the main thread. Disk work never runs here: it goes to the DyMarket-IO thread.
 */
public class Scheduler {
    public static final boolean FOLIA = present("io.papermc.paper.threadedregions.RegionizedServer");

    /** A scheduled task that can be cancelled whichever scheduler started it. */
    public interface Task {
        void cancel();
    }

    private final Plugin plugin;

    public Scheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Runs on the thread that owns the entity; on Folia nothing runs if the entity is gone. */
    public Task runLater(Entity entity, Runnable task, long ticks) {
        if (FOLIA) return FoliaBridge.entity(plugin, entity, task, Math.max(1L, ticks));
        BukkitTask handle = plugin.getServer().getScheduler().runTaskLater(plugin, task, Math.max(0L, ticks));
        return handle::cancel;
    }

    public Task run(Entity entity, Runnable task) {
        return runLater(entity, task, 1L);
    }

    /**
     * Runs on the thread that owns the entity, or {@code retired} instead when the entity is gone (a player who
     * left). Exactly one of the two runs; {@code retired} may run on any thread.
     */
    public Task run(Entity entity, Runnable task, Runnable retired) {
        if (FOLIA) return FoliaBridge.entity(plugin, entity, task, retired);
        BukkitTask handle = plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean present = entity instanceof org.bukkit.entity.Player player ? player.isOnline() : entity.isValid();
            if (present) task.run();
            else retired.run();
        });
        return handle::cancel;
    }

    /** Runs on the thread that owns the location. */
    public void run(Location location, Runnable task) {
        if (FOLIA) FoliaBridge.region(plugin, location, task);
        else plugin.getServer().getScheduler().runTask(plugin, task);
    }

    public Task runGlobalTimer(Runnable task, long delay, long period) {
        if (FOLIA) return FoliaBridge.globalTimer(plugin, task, Math.max(1L, delay), Math.max(1L, period));
        BukkitTask handle = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delay, period);
        return handle::cancel;
    }

    public Task runGlobal(Runnable task) {
        if (FOLIA) return FoliaBridge.globalTimer(plugin, task, 1L, -1L);
        BukkitTask handle = plugin.getServer().getScheduler().runTask(plugin, task);
        return handle::cancel;
    }

    public Task runGlobalLater(Runnable task, long ticks) {
        if (FOLIA) return FoliaBridge.globalTimer(plugin, task, Math.max(1L, ticks), -1L);
        BukkitTask handle = plugin.getServer().getScheduler().runTaskLater(plugin, task, Math.max(0L, ticks));
        return handle::cancel;
    }

    public void runAsync(Runnable task) {
        if (FOLIA) FoliaBridge.async(plugin, task);
        else plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    /** A repeating task off the server thread; period in ticks (50 ms each). */
    public Task runAsyncTimer(Runnable task, long delay, long period) {
        if (FOLIA) return FoliaBridge.asyncTimer(plugin, task, Math.max(1L, delay) * 50L, Math.max(1L, period) * 50L);
        BukkitTask handle = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        return handle::cancel;
    }

    public void cancelAll() {
        if (FOLIA) FoliaBridge.cancelAll(plugin);
        else plugin.getServer().getScheduler().cancelTasks(plugin);
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, Scheduler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /** Only loaded on Folia, so Spigot never links these classes. */
    private static final class FoliaBridge {
        static Task entity(Plugin plugin, Entity entity, Runnable task, long ticks) {
            var handle = entity.getScheduler().runDelayed(plugin, scheduled -> task.run(), null, ticks);
            return () -> { if (handle != null) handle.cancel(); };
        }

        static Task entity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
            var handle = entity.getScheduler().run(plugin, scheduled -> task.run(), retired);
            if (handle == null) {
                retired.run();
                return () -> { };
            }
            return handle::cancel;
        }

        static void region(Plugin plugin, Location location, Runnable task) {
            plugin.getServer().getRegionScheduler().execute(plugin, location, task);
        }

        static Task globalTimer(Plugin plugin, Runnable task, long delay, long period) {
            var scheduler = plugin.getServer().getGlobalRegionScheduler();
            var handle = period < 0 ? scheduler.runDelayed(plugin, scheduled -> task.run(), delay)
                    : scheduler.runAtFixedRate(plugin, scheduled -> task.run(), delay, period);
            return handle::cancel;
        }

        static void async(Plugin plugin, Runnable task) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, scheduled -> task.run());
        }

        static Task asyncTimer(Plugin plugin, Runnable task, long delayMillis, long periodMillis) {
            var handle = plugin.getServer().getAsyncScheduler()
                    .runAtFixedRate(plugin, scheduled -> task.run(), delayMillis, periodMillis, TimeUnit.MILLISECONDS);
            return handle::cancel;
        }

        static void cancelAll(Plugin plugin) {
            plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
            plugin.getServer().getAsyncScheduler().cancelTasks(plugin);
        }
    }
}
