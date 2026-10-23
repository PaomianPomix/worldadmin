package net.shiroha233.roadweaver.mixin.neoforge;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Retained for the upstream mixin configuration. Initial road planning is now demand-driven;
 * prepareLevels must not preload road files or wait for road futures on the game thread.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {}
