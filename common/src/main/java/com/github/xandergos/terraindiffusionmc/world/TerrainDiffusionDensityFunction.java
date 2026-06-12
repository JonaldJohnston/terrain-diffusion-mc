package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;


public class TerrainDiffusionDensityFunction implements DensityFunction {

    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC =
            MapCodec.unit(TerrainDiffusionDensityFunction::new);

    public static final TerrainDiffusionDensityFunction INSTANCE =
            new TerrainDiffusionDensityFunction();

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        int targetHeight = LocalTerrainProvider.surfaceHeight(pos.blockX(), pos.blockZ());
        if (targetHeight == Integer.MIN_VALUE) {
            return 1.0;
        }
        return pos.blockY() < targetHeight ? 1.0 : -1.0;
    }

    @Override
    public void fillArray(double[] densities, DensityFunction.ContextProvider applier) {
        applier.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
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
