package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.resources.ResourceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Splices the modpack's merged overworld surface rules into this mod's surface rule.
 *
 * <p>Our dimension uses its own noise settings, so mods that attach surface rules to
 * {@code minecraft:overworld} (Terralith via datapack merge, BWG/RU via TerraBlender)
 * never get them applied here — their biomes fall through to our grass/dirt default.
 * At world load we capture the fully merged {@code minecraft:overworld} rule (plus
 * TerraBlender's namespaced rules when present) and rebuild our rule as:
 *
 * <pre>  [our bedrock/deepslate/snow + biome-specific rules]  — unchanged behavior
 *  [captured modded + vanilla overworld rules]          — handles modded biomes
 *  [our grass/dirt defaults]                            — fallback if nothing matched</pre>
 *
 * <p>The specific/default halves mirror surface_rule in
 * {@code worldgen/noise_settings/terrain_diffusion.json}, which remains the standalone
 * fallback if the SurfaceSystem mixin is not applied.
 */
public final class SurfaceRuleInjector {

    private static final Logger LOG = LoggerFactory.getLogger(SurfaceRuleInjector.class);

    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(TerrainDiffusionLifecycle.MOD_ID, "snowy_taiga_sparse"));

    /** The rule instance parsed from our noise settings JSON; used as the identity marker. */
    private static volatile SurfaceRules.RuleSource markerRule;
    /** The replacement rule with modded surface rules spliced in. */
    private static volatile SurfaceRules.RuleSource combinedRule;

    private SurfaceRuleInjector() {
    }

    /** Called from onWorldLoad with the loaded world's registries. */
    public static void captureFromRegistry(RegistryAccess registryAccess) {
        markerRule = null;
        combinedRule = null;
        Registry<NoiseGeneratorSettings> registry = registryAccess.registryOrThrow(Registries.NOISE_SETTINGS);
        NoiseGeneratorSettings ours = registry.get(ResourceLocation.fromNamespaceAndPath(
                TerrainDiffusionLifecycle.MOD_ID, "terrain_diffusion"));
        if (ours == null) {
            return;
        }
        NoiseGeneratorSettings overworld = registry.get(NoiseGeneratorSettings.OVERWORLD);
        SurfaceRules.RuleSource modded = overworld == null ? null
                : withTerraBlenderRules(overworld.surfaceRule());
        markerRule = ours.surfaceRule();
        combinedRule = buildCombined(modded);
        LOG.info("Surface rules ready: modded overworld rules {}",
                modded == null ? "not found (using built-in only)" : "spliced in");
    }

    /**
     * Swap hook called from the SurfaceSystem mixin for every dimension; only replaces
     * the rule when it is identical to our settings' parsed rule.
     */
    public static SurfaceRules.RuleSource swap(SurfaceRules.RuleSource original) {
        SurfaceRules.RuleSource marker = markerRule;
        SurfaceRules.RuleSource combined = combinedRule;
        return (marker != null && original == marker && combined != null) ? combined : original;
    }

    /**
     * Wraps the overworld rule with TerraBlender's namespaced mod rules when TerraBlender
     * is installed (BWG and Regions Unexplored register their rules there). If TerraBlender
     * already merged itself into the overworld rule this just evaluates the same rules
     * twice, which is harmless — first match wins.
     */
    private static SurfaceRules.RuleSource withTerraBlenderRules(SurfaceRules.RuleSource overworldRule) {
        try {
            Class<?> manager = Class.forName("terrablender.api.SurfaceRuleManager");
            Class<?> categoryClass = Class.forName("terrablender.api.SurfaceRuleManager$RuleCategory");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object category = Enum.valueOf((Class<Enum>) categoryClass, "OVERWORLD");
            Method getNamespacedRules = manager.getMethod(
                    "getNamespacedRules", categoryClass, SurfaceRules.RuleSource.class);
            Object result = getNamespacedRules.invoke(null, category, overworldRule);
            if (result instanceof SurfaceRules.RuleSource ruleSource) {
                LOG.info("TerraBlender surface rules captured");
                return ruleSource;
            }
        } catch (ClassNotFoundException absent) {
            // TerraBlender not installed; the overworld rule alone covers datapack-based mods.
        } catch (Throwable t) {
            LOG.warn("Failed to query TerraBlender surface rules, using overworld rules only: {}", t.toString());
        }
        return overworldRule;
    }

    // =========================================================================
    // Code mirror of the JSON surface rule, split so modded rules can sit
    // between the biome-specific rules and the defaults.
    // =========================================================================

    private static SurfaceRules.RuleSource buildCombined(SurfaceRules.RuleSource modded) {
        SurfaceRules.ConditionSource onFloor = SurfaceRules.stoneDepthCheck(0, false, 0, CaveSurface.FLOOR);
        SurfaceRules.ConditionSource withinDepth4 = SurfaceRules.stoneDepthCheck(4, false, 0, CaveSurface.FLOOR);

        List<SurfaceRules.RuleSource> parts = new ArrayList<>();
        parts.add(SurfaceRules.ifTrue(
                SurfaceRules.verticalGradient("minecraft:bedrock_floor",
                        VerticalAnchor.absolute(-60), VerticalAnchor.absolute(-55)),
                SurfaceRules.state(Blocks.BEDROCK.defaultBlockState())));
        parts.add(SurfaceRules.ifTrue(
                SurfaceRules.verticalGradient("minecraft:deepslate",
                        VerticalAnchor.absolute(0), VerticalAnchor.absolute(8)),
                SurfaceRules.state(Blocks.DEEPSLATE.defaultBlockState())));
        parts.add(SurfaceRules.ifTrue(
                SurfaceRules.isBiome(Biomes.SNOWY_PLAINS, Biomes.SNOWY_TAIGA, SNOWY_TAIGA_SPARSE),
                SurfaceRules.ifTrue(SurfaceRules.stoneDepthCheck(-1, false, 0, CaveSurface.FLOOR),
                        SurfaceRules.ifTrue(SurfaceRules.waterBlockCheck(0, 0),
                                SurfaceRules.state(Blocks.SNOW.defaultBlockState())))));
        parts.add(SurfaceRules.ifTrue(onFloor, SurfaceRules.sequence(
                biomeBlock(Blocks.SAND, Biomes.DESERT),
                biomeBlock(Blocks.RED_SAND, Biomes.BADLANDS),
                biomeBlock(Blocks.SNOW_BLOCK, Biomes.SNOWY_SLOPES),
                biomeBlock(Blocks.SNOW_BLOCK, Biomes.FROZEN_PEAKS),
                biomeBlock(Blocks.STONE, Biomes.STONY_PEAKS),
                biomeBlock(Blocks.STONE, Biomes.WINDSWEPT_HILLS),
                biomeBlock(Blocks.SAND, Biomes.WARM_OCEAN),
                biomeBlock(Blocks.SAND, Biomes.OCEAN),
                biomeBlock(Blocks.GRAVEL, Biomes.COLD_OCEAN, Biomes.FROZEN_OCEAN))));
        parts.add(SurfaceRules.ifTrue(withinDepth4, SurfaceRules.sequence(
                biomeBlock(Blocks.SANDSTONE, Biomes.DESERT),
                biomeBlock(Blocks.TERRACOTTA, Biomes.BADLANDS),
                biomeBlock(Blocks.STONE, Biomes.SNOWY_SLOPES),
                biomeBlock(Blocks.STONE, Biomes.FROZEN_PEAKS),
                biomeBlock(Blocks.STONE, Biomes.STONY_PEAKS, Biomes.WINDSWEPT_HILLS),
                biomeBlock(Blocks.SAND, Biomes.WARM_OCEAN),
                biomeBlock(Blocks.GRAVEL, Biomes.OCEAN, Biomes.COLD_OCEAN, Biomes.FROZEN_OCEAN))));

        if (modded != null) {
            parts.add(modded);
        }

        parts.add(SurfaceRules.ifTrue(onFloor, SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(62), 0),
                        SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState())),
                SurfaceRules.state(Blocks.DIRT.defaultBlockState()))));
        parts.add(SurfaceRules.ifTrue(withinDepth4, SurfaceRules.state(Blocks.DIRT.defaultBlockState())));

        return SurfaceRules.sequence(parts.toArray(new SurfaceRules.RuleSource[0]));
    }

    @SafeVarargs
    private static SurfaceRules.RuleSource biomeBlock(
            net.minecraft.world.level.block.Block block, ResourceKey<Biome>... biomes) {
        return SurfaceRules.ifTrue(SurfaceRules.isBiome(biomes),
                SurfaceRules.state(block.defaultBlockState()));
    }
}
