package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.SurfaceRuleInjector;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Replaces the surface rule for Terrain Diffusion dimensions with the runtime-combined
 * rule from {@link SurfaceRuleInjector} (modded overworld surface rules spliced in).
 * Inert for every other dimension: the swap is keyed on rule-instance identity.
 */
@Mixin(SurfaceSystem.class)
public class SurfaceSystemMixin {

    @ModifyVariable(method = "buildSurface", at = @At("HEAD"), argsOnly = true)
    private SurfaceRules.RuleSource terrainDiffusionMc$spliceModdedSurfaceRules(SurfaceRules.RuleSource original) {
        return SurfaceRuleInjector.swap(original);
    }
}
