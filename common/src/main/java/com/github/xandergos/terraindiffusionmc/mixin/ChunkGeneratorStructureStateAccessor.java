package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the biome source so structure placement can tell whether it is our dimension. */
@Mixin(ChunkGeneratorStructureState.class)
public interface ChunkGeneratorStructureStateAccessor {
    @Accessor("biomeSource")
    BiomeSource terrainDiffusionMc$biomeSource();
}
