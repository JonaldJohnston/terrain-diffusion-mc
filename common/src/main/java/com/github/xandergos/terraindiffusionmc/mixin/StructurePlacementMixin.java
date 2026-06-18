package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Thins out structures in the Terrain Diffusion dimension by gating each candidate
 * chunk through an extra probability, controlled by {@code structures.density} in the
 * config. The gate is deterministic per (seed, chunk, structure) — mirroring vanilla's
 * own {@code probabilityReducer} — so structures don't flicker between worldgen passes.
 *
 * <p>Scoped to our biome source by identity; inert in every other dimension. Strongholds
 * ({@link ConcentricRingsStructurePlacement}) are exempt: they are underground rather than
 * surface clutter and are required for End progression.
 */
@Mixin(StructurePlacement.class)
public abstract class StructurePlacementMixin {

    /** Distinct from vanilla salts so our gate is independent of the structure's own frequency roll. */
    private static final int TERRAIN_DIFFUSION_SALT = 0x7D1FF; // "td" thinning salt

    @Shadow @Final private int salt;

    @Inject(method = "isStructureChunk", at = @At("RETURN"), cancellable = true)
    private void terrainDiffusionMc$thinStructures(ChunkGeneratorStructureState state, int chunkX, int chunkZ,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return; // Structure already rejected this chunk; nothing to thin.
        }
        if ((Object) this instanceof ConcentricRingsStructurePlacement) {
            return; // Leave strongholds alone.
        }
        float density = TerrainDiffusionConfig.structureDensity();
        if (density >= 1.0f) {
            return;
        }
        if (!(((ChunkGeneratorStructureStateAccessor) state).terrainDiffusionMc$biomeSource()
                instanceof TerrainDiffusionBiomeSource)) {
            return; // Not our dimension.
        }
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureWithSalt(state.getLevelSeed(), chunkX, chunkZ, this.salt ^ TERRAIN_DIFFUSION_SALT);
        if (random.nextFloat() >= density) {
            cir.setReturnValue(false);
        }
    }
}
