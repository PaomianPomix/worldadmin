package dev.worldadmin.compat;

import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Coordinates vanilla chunk pregeneration with Distant Horizons 3.3.3.
 * All methods must be called on the Minecraft server thread. This lease is
 * idempotent, not reference counted: the scheduler owns one bridge per server
 * and releases it only after its outstanding vanilla generation requests drain.
 *
 * <p>DH's public API writes the temporary override. A read-only check of its
 * public core config entry identifies its owner, since the API does not expose
 * ownership. That check is deliberately restricted to the inspected 3.3.3
 * version. No DH configuration file is edited and no hard dependency is added.
 * Disabling generation stops new DH work; it cannot interrupt work already
 * executing inside another mod's generator.</p>
 */
public final class DistantHorizonsBridge {
    private static final System.Logger LOGGER = System.getLogger(DistantHorizonsBridge.class.getName());
    private static final String API = "com.seibel.distanthorizons.api.";
    private static final String SUPPORTED_VERSION = "3.3.3";

    private final boolean present;
    private final String owner = "World Admin pregeneration (" + UUID.randomUUID() + ")";
    private Access access;
    private boolean ownsOverride;
    private boolean failed;
    private boolean loggedFailure;
    private String status;

    public DistantHorizonsBridge() {
        present = ModList.get().isLoaded("distanthorizons");
        status = present ? "Waiting for Distant Horizons initialization" : "Distant Horizons not installed";
    }

    /**
     * Returns true only when DH is absent or its generation is confirmed disabled.
     * Call before every batch, including while a lease is held: another mod may
     * change the shared override. A false result means the scheduler must wait.
     * Existing non-disabled API overrides are never overwritten.
     */
    public boolean acquire() {
        if (!present) return true;
        if (failed) return false;
        try {
            if (access == null && !initialize()) return false;
            Object currentOverride = access.apiValue();
            if (ownsOverride && !isOurOverride(currentOverride)) {
                // Another owner cleared or replaced our override. Do not restore
                // it later, even if their replacement is also DISABLED.
                ownsOverride = false;
            }

            if (access.disabled.equals(access.value())) {
                status = ownsOverride ? "Distant Horizons generation suspended by World Admin"
                        : "Distant Horizons generation already disabled; existing settings preserved";
                return true;
            }

            if (currentOverride != null) {
                status = "Pregeneration waiting: Distant Horizons generation is controlled by another API override";
                return false;
            }
            if (!Boolean.TRUE.equals(access.canOverride.invoke(access.configValue))) {
                status = "Pregeneration waiting: Distant Horizons rejected API control";
                return false;
            }

            // Mark before invocation so release can recover even if a DH change
            // listener throws after the value has been written.
            ownsOverride = true;
            boolean accepted = Boolean.TRUE.equals(access.setValue.invoke(access.configValue, access.disabled, owner));
            if (!accepted || !isOurOverride(access.apiValue()) || !access.disabled.equals(access.value())) {
                status = "Pregeneration waiting: Distant Horizons suspension could not be confirmed";
                return false;
            }
            status = "Distant Horizons generation suspended by World Admin";
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            fail("Distant Horizons coordination failed; pregeneration is paused", error);
            return false;
        }
    }

    /** Restores the user's configuration only if this exact lease still owns the override. */
    public void release() {
        if (!ownsOverride || access == null) return;
        try {
            if (!isOurOverride(access.apiValue())) {
                ownsOverride = false;
                status = "Distant Horizons override changed externally; left unchanged";
                return;
            }
            if (!Boolean.TRUE.equals(access.clearValue.invoke(access.configValue))) {
                fail("Distant Horizons rejected release of the pregeneration override", null);
                return;
            }
            ownsOverride = false;
            status = "Distant Horizons user configuration restored";
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            fail("Unable to release the Distant Horizons pregeneration override", error);
        }
    }

    public String status() {
        return status;
    }

    private boolean initialize() throws ReflectiveOperationException {
        Class<?> dhApi = Class.forName(API + "DhApi");
        String version = String.valueOf(dhApi.getMethod("getModVersion").invoke(null));
        if (!SUPPORTED_VERSION.equals(version)) {
            fail("Pregeneration paused: Distant Horizons " + version
                    + " is outside the verified 3.3.3 adapter", null);
            return false;
        }
        Object configs = Class.forName(API + "DhApi$Delayed").getField("configs").get(null);
        if (configs == null) {
            status = "Pregeneration waiting for Distant Horizons initialization";
            return false;
        }

        Class<?> configApi = Class.forName(API + "interfaces.config.IDhApiConfig");
        Object worldGenerator = configApi.getMethod("worldGenerator").invoke(configs);
        Class<?> generationApi = Class.forName(API + "interfaces.config.both.IDhApiWorldGenerationConfig");
        Object configValue = generationApi.getMethod("GeneratorPlan").invoke(worldGenerator);
        Object disabled = Class.forName(API + "enums.worldGeneration.EDhApiGeneratorPlan").getField("DISABLED").get(null);

        // Read ownership from DH's public core entry; mutate only through its API.
        Object coreEntry = Class.forName("com.seibel.distanthorizons.core.config.Config$Common$WorldGenerator")
                .getField("generatorPlan").get(null);
        Method getOwner = coreEntry.getClass().getMethod("getApiUser");
        Class<?> valueApi = Class.forName(API + "interfaces.config.IDhApiConfigValue");
        access = new Access(configValue, disabled, coreEntry, getOwner,
                valueApi.getMethod("getValue"), valueApi.getMethod("getApiValue"),
                valueApi.getMethod("getCanBeOverrodeByApi"),
                valueApi.getMethod("setValue", Object.class, String.class), valueApi.getMethod("clearValue"));
        return true;
    }

    private boolean isOurOverride(Object apiValue) throws ReflectiveOperationException {
        return access.disabled.equals(apiValue) && owner.equals(access.getOwner.invoke(access.coreEntry));
    }

    private void fail(String message, Throwable error) {
        failed = true;
        status = message;
        if (loggedFailure) return;
        loggedFailure = true;
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : error;
        if (cause == null) LOGGER.log(System.Logger.Level.WARNING, message);
        else LOGGER.log(System.Logger.Level.WARNING, message, cause);
    }

    private record Access(Object configValue, Object disabled, Object coreEntry, Method getOwner,
                          Method getValue, Method getApiValue, Method canOverride,
                          Method setValue, Method clearValue) {
        Object value() throws ReflectiveOperationException {
            return getValue.invoke(configValue);
        }

        Object apiValue() throws ReflectiveOperationException {
            return getApiValue.invoke(configValue);
        }
    }
}
