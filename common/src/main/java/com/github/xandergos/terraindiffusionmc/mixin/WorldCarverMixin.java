package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.CarverHydrology;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Delegates dry-land cave hydrology to {@link CarverHydrology}. */
@Mixin(WorldCarver.class)
public class WorldCarverMixin {

    @Inject(method = "getCarveState", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusionMc$dryLandCaves(CarvingContext context, CarverConfiguration config,
                                                 BlockPos pos, Aquifer aquifer,
                                                 CallbackInfoReturnable<BlockState> cir) {
        BlockState override = CarverHydrology.carveStateOverride(context, config, pos);
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
