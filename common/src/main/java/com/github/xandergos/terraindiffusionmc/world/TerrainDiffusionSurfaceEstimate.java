package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;


/**
 * Conservative surface estimate for {@code initial_density_without_jaggedness}.
 *
 * Vanilla's aquifer probes for ocean water at {@code preliminarySurfaceLevel + 8},
 * and vanilla's own initial-density slope puts that estimate ~20-30 blocks below
 * the true surface. Our binary density function crosses the 0.390625 scan
 * threshold exactly at the surface, so over shallow ocean shelves
 * (seafloor within 8 blocks of sea level) the probe lands above sea level,
 * finds air, and leaves the water column dry. Shifting the step down by
 * {@link #SURFACE_MARGIN} restores the conservative estimate the aquifer
 * expects; it is never used for actual block placement.
 */
public class TerrainDiffusionSurfaceEstimate implements DensityFunction.SimpleFunction {

    public static final int SURFACE_MARGIN = 16;

    public static final MapCodec<TerrainDiffusionSurfaceEstimate> CODEC =
            MapCodec.unit(TerrainDiffusionSurfaceEstimate::new);

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        return TerrainDiffusionDensityFunction.INSTANCE.compute(
                new DensityFunction.SinglePointContext(
                        pos.blockX(), pos.blockY() + SURFACE_MARGIN, pos.blockZ()));
    }

    @Override
    public double minValue() {
        return -1;
    }

    @Override
    public double maxValue() {
        return 1;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
