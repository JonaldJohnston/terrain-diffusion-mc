package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Map.entry;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    private static final Logger LOG = LoggerFactory.getLogger(TerrainDiffusionBiomeSource.class);

    private static final ResourceKey<Biome> FOREST_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "forest_sparse"));
    private static final ResourceKey<Biome> TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "taiga_sparse"));
    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "snowy_taiga_sparse"));

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));


    /** Climate-category names keyed by palette id; each maps to the optional datapack tag
     *  {@code terrain-diffusion-mc:variants/<name>} whose members are substitutes for that category. */
    private static final Map<Short, String> VARIANT_TAG_NAMES = Map.ofEntries(
            entry((short) 1, "plains"),
            entry((short) 3, "snowy_plains"),
            entry((short) 5, "desert"),
            entry((short) 6, "swamp"),
            entry((short) 8, "forest"),
            entry((short) 15, "taiga"),
            entry((short) 16, "snowy_taiga"),
            entry((short) 17, "savanna"),
            entry((short) 19, "windswept_hills"),
            entry((short) 23, "jungle"),
            entry((short) 26, "badlands"),
            entry((short) 29, "meadow"),
            entry((short) 31, "grove"),
            entry((short) 32, "snowy_slopes"),
            entry((short) 33, "frozen_peaks"),
            entry((short) 35, "stony_peaks"),
            entry((short) 41, "warm_ocean"),
            entry((short) 44, "ocean"),
            entry((short) 45, "deep_ocean"),
            entry((short) 46, "cold_ocean"),
            entry((short) 48, "frozen_ocean"),
            entry((short) 108, "forest_sparse"),
            entry((short) 115, "taiga_sparse"),
            entry((short) 116, "snowy_taiga_sparse"));

    /** Side length (log2, in blocks) of a variant patch. The jittered-Voronoi layout is shared
     *  by every category and its patches (~2048 blocks) are much larger than a typical climate
     *  region, so each contiguous region of one climate resolves to a single variant biome
     *  instead of a mix. */
    private static final int VARIANT_CELL_BITS = 11;

    private HolderGetter<Biome> biomeLookup;
    private Map<Short, Holder<Biome>> biomeIdMap = null;
    /** Dense biome-id lookup for the hot path; avoids Short boxing/hashing. Ids are small (max 116). */
    private Holder<Biome>[] biomeIdArray = null;
    private Holder<Biome> defaultBiome = null;
    /** Per-category variant lists (base biome first, then resolved tag members); null entry = no variants.
     *  Built lazily and only cached once tags are bound, so a pre-bind call retries later. */
    private Holder<Biome>[][] variantTable = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @SuppressWarnings("unchecked")
    private void requireBiomeIdMap() {
        if (biomeIdMap == null) {
            Map<Short, Holder<Biome>> map = Map.ofEntries(
                    entry((short) 1, this.biomeLookup.getOrThrow(Biomes.PLAINS)),
                    entry((short) 3, this.biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS)),
                    entry((short) 5, this.biomeLookup.getOrThrow(Biomes.DESERT)),
                    entry((short) 6, this.biomeLookup.getOrThrow(Biomes.SWAMP)),
                    entry((short) 8, this.biomeLookup.getOrThrow(Biomes.FOREST)),
                    entry((short) 15, this.biomeLookup.getOrThrow(Biomes.TAIGA)),
                    entry((short) 16, this.biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA)),
                    entry((short) 17, this.biomeLookup.getOrThrow(Biomes.SAVANNA)),
                    entry((short) 19, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_HILLS)),
                    entry((short) 23, this.biomeLookup.getOrThrow(Biomes.JUNGLE)),
                    entry((short) 26, this.biomeLookup.getOrThrow(Biomes.BADLANDS)),
                    entry((short) 29, this.biomeLookup.getOrThrow(Biomes.MEADOW)),
                    entry((short) 31, this.biomeLookup.getOrThrow(Biomes.GROVE)),
                    entry((short) 32, this.biomeLookup.getOrThrow(Biomes.SNOWY_SLOPES)),
                    entry((short) 33, this.biomeLookup.getOrThrow(Biomes.FROZEN_PEAKS)),
                    entry((short) 35, this.biomeLookup.getOrThrow(Biomes.STONY_PEAKS)),
                    entry((short) 41, this.biomeLookup.getOrThrow(Biomes.WARM_OCEAN)),
                    entry((short) 44, this.biomeLookup.getOrThrow(Biomes.OCEAN)),
                    entry((short) 45, this.biomeLookup.getOrThrow(Biomes.DEEP_OCEAN)),
                    entry((short) 46, this.biomeLookup.getOrThrow(Biomes.COLD_OCEAN)),
                    entry((short) 48, this.biomeLookup.getOrThrow(Biomes.FROZEN_OCEAN)),
                    entry((short) 108, this.biomeLookup.getOrThrow(FOREST_SPARSE)),
                    entry((short) 115, this.biomeLookup.getOrThrow(TAIGA_SPARSE)),
                    entry((short) 116, this.biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE))
            );
            Holder<Biome>[] array = new Holder[128];
            for (Map.Entry<Short, Holder<Biome>> e : map.entrySet()) {
                array[e.getKey()] = e.getValue();
            }
            this.defaultBiome = map.get((short) 1);
            this.biomeIdArray = array;
            this.biomeIdMap = map;
        }
    }

    /** Resolves the per-category variant tags. No-ops (without caching) if biome tags are not
     *  bound yet, so the table is rebuilt on the next call once datapack loading finishes.
     *  Each candidate is admitted only if the combined set still has a consistent global
     *  feature order — NoiseBasedChunkGenerator topologically sorts the placed features of
     *  every possible biome, and a modded biome whose feature list contradicts the rest
     *  (e.g. some BWG biomes vs vanilla grove) would crash chunk generation with
     *  "Feature order cycle found". Conflicting biomes are skipped and logged instead. */
    @SuppressWarnings("unchecked")
    private void requireVariantTable() {
        if (variantTable != null) return;
        requireBiomeIdMap();

        // Resolve tag members first; an unbound-tag IllegalStateException here means we were
        // called before datapack loading finished, so retry on a later call.
        List<Short> categoryIds = new ArrayList<>(VARIANT_TAG_NAMES.keySet());
        categoryIds.sort(null);
        Map<Short, List<Holder<Biome>>> resolved = new java.util.LinkedHashMap<>();
        try {
            for (Short id : categoryIds) {
                TagKey<Biome> tag = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(
                        TerrainDiffusionLifecycle.MOD_ID, "variants/" + VARIANT_TAG_NAMES.get(id)));
                Optional<HolderSet.Named<Biome>> members = this.biomeLookup.get(tag);
                if (members.isEmpty()) continue;
                Holder<Biome> base = biomeIdMap.get(id);
                List<Holder<Biome>> list = new ArrayList<>();
                for (Holder<Biome> member : members.get()) {
                    if (!member.equals(base)) list.add(member);
                }
                if (!list.isEmpty()) resolved.put(id, list);
            }
        } catch (IllegalStateException unboundTags) {
            return;
        }

        List<Holder<Biome>> accepted = new ArrayList<>(biomeIdMap.values());
        java.util.Set<Holder<Biome>> acceptedSet = new java.util.HashSet<>(accepted);
        Holder<Biome>[][] table = new Holder[biomeIdArray.length][];
        for (Map.Entry<Short, List<Holder<Biome>>> e : resolved.entrySet()) {
            List<Holder<Biome>> list = new ArrayList<>();
            list.add(biomeIdMap.get(e.getKey()));
            for (Holder<Biome> member : e.getValue()) {
                if (acceptedSet.contains(member)) {
                    list.add(member);
                    continue;
                }
                accepted.add(member);
                if (hasConsistentFeatureOrder(accepted)) {
                    acceptedSet.add(member);
                    list.add(member);
                } else {
                    accepted.remove(accepted.size() - 1);
                    LOG.warn("Skipping biome variant {}: its feature order conflicts with the other biomes (would crash worldgen)",
                            member.unwrapKey().map(k -> k.location().toString()).orElse(member.toString()));
                }
            }
            if (list.size() > 1) {
                table[e.getKey()] = list.toArray(new Holder[0]);
            }
        }
        this.variantTable = table;
    }

    private static boolean hasConsistentFeatureOrder(List<Holder<Biome>> biomes) {
        try {
            FeatureSorter.buildFeaturesPerStep(List.copyOf(biomes),
                    biome -> biome.value().getGenerationSettings().features(), false);
            return true;
        } catch (IllegalStateException cycle) {
            return false;
        }
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        requireVariantTable();
        Stream<Holder<Biome>> base = biomeIdMap.values().stream();
        Holder<Biome>[][] table = this.variantTable;
        if (table == null) return base;
        return Stream.concat(base, Arrays.stream(table).filter(Objects::nonNull).flatMap(Arrays::stream));
    }

    /** Jittered-Voronoi pick: hash the 3x3 neighborhood of ~2048-block cells around the position,
     *  take the nearest jittered cell center, and use its hash to choose a variant. The layout is
     *  the same for every category, so all variant changes happen on the same sparse patch
     *  boundaries and a contiguous climate region is normally a single variant throughout. */
    private static int variantIndex(long seed, int blockX, int blockZ, int count) {
        int cellX = blockX >> VARIANT_CELL_BITS;
        int cellZ = blockZ >> VARIANT_CELL_BITS;
        int jitterMask = (1 << VARIANT_CELL_BITS) - 1;
        long bestDist = Long.MAX_VALUE;
        long bestHash = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                long h = mix(seed, cx, cz);
                int centerX = (cx << VARIANT_CELL_BITS) + (int) (h & jitterMask);
                int centerZ = (cz << VARIANT_CELL_BITS) + (int) ((h >>> 20) & jitterMask);
                long ddx = centerX - blockX;
                long ddz = centerZ - blockZ;
                long d2 = ddx * ddx + ddz * ddz;
                if (d2 < bestDist) {
                    bestDist = d2;
                    bestHash = h;
                }
            }
        }
        return (int) ((bestHash >>> 40) % count);
    }

    private static long mix(long seed, int cx, int cz) {
        long h = seed + cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);

        LocalTerrainProvider.TileMemo memo = LocalTerrainProvider.tileAt(blockX, blockZ);
        HeightmapData data = memo.data;
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - memo.blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - memo.blockStartZ));
            short biomeId = data.biomeIds[localZ][localX];
            if (biomeId >= 0 && biomeId < biomeIdArray.length) {
                Holder<Biome> entry = biomeIdArray[biomeId];
                if (entry != null) {
                    requireVariantTable();
                    Holder<Biome>[][] table = this.variantTable;
                    Holder<Biome>[] variants = table == null ? null : table[biomeId];
                    if (variants != null) {
                        return variants[variantIndex(memo.seed, blockX, blockZ, variants.length)];
                    }
                    return entry;
                }
            }
        }

        return defaultBiome;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval, Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return null;
    }
}
