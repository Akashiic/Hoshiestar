/*
 * Hoshiestar: servidor hibrido Forge + Bukkit para Minecraft 1.7.10, fork do Crucible.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo sob os termos da
 * GNU General Public License publicada pela Free Software Foundation, versao 3 da Licenca ou
 * (a seu criterio) qualquer versao posterior. Distribuido SEM QUALQUER GARANTIA.
 * Veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.paralelo;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * O que o passo 0 (e o dump das classes finais do passo 2) revisou, embutido no jar (plano do Degrau B, secao 6, "Travas de
 * versao"): os jars de terceiros que poem codigo no caminho das classes liberadas, com o SHA-256 revisado; os mixins
 * mesclados nas classes do caminho que tem veredito; os observadores e watchers do DragonAPI revisados; a classe do
 * {@code world.rand} esperada. Liberar coisa nova exige revisao e build. Sem Minecraft.
 */
public final class Travas {

    private Travas() {
    }

    /**
     * Um jar travado: rotulo, um recurso que so ele tem (para achar o arquivo sem carregar classe), SHA-256, classes e os
     * prefixos de pacote do codigo dele (revisao do passo 3, I1: recurso-sonda ausente com mixin ou transformador de um
     * destes pacotes presente = o jar esta, mudado; nao e "jar ausente").
     */
    public static final class Trava {
        public final String rotulo;
        public final String recurso;
        public final String sha256;
        /** Classes (nomes do config) cujo caminho tem codigo deste jar. */
        public final List<String> classes;
        /** Prefixos (nome binario com ponto, terminando em ponto) dos mixins e transformadores deste jar. */
        public final List<String> pacotes;

        Trava(String rotulo, String recurso, String sha256, List<String> classes, String... pacotes) {
            this.rotulo = rotulo;
            this.recurso = recurso;
            this.sha256 = sha256;
            this.classes = classes;
            this.pacotes = Collections.unmodifiableList(Arrays.asList(pacotes));
        }
    }

    /** A trava de rotulo {@code r}, ou null. */
    public static Trava trava(String r) {
        for (Trava t : JARS) {
            if (t.rotulo.equals(r)) {
                return t;
            }
        }
        return null;
    }

    static final List<String> TODAS = Collections.unmodifiableList(Arrays.asList("mesa", "bau", "bau_ender"));
    static final List<String> SO_MESA = Collections.singletonList("mesa");

    /**
     * Passo 0, secao 7 (mais o AM2.5-LTS, injecao nova em getClosestPlayer achada no dump do passo 2; a mesa so volta com
     * esse veredito). EtFuturum e AM2 so tocam o caminho da mesa (getClosestPlayer); os outros, o caminho comum.
     */
    public static final List<Trava> JARS = Collections.unmodifiableList(Arrays.asList(
            new Trava("AkashicBellow", "com/akashiic/bellow/mixin/early/common/MixinWorld_Deep.class",
                    "f78dcdfd6d0eb7ce476ebcad5613c56de927baf49955abacc697137a510c62c3", TODAS, "com.akashiic.bellow."),
            new Trava("hodgepodge", "com/mitchej123/hodgepodge/config/FixesConfig.class",
                    "0bf958115463c9841610e2d0183dfde26db46e3a7756b8eb88740e382eaf1184", TODAS, "com.mitchej123.hodgepodge."),
            new Trava("endlessids", "com/falsepattern/endlessids/mixin/mixins/common/blockitem/common/ExtendedBlockStorageMixin.class",
                    "9be2b3ae458a72f2cdeb3fb30b77d8528a1dcd64ef08fc69720b79335e5ce0d7", TODAS, "com.falsepattern.endlessids."),
            new Trava("DragonAPI", "Reika/DragonAPI/Instantiable/Event/TileUpdateEvent.class",
                    "409ea21b07159ea52bf90e3a5c508a43a22159e3434ced79a14276d313d581ca", TODAS, "Reika.DragonAPI."),
            new Trava("Akashic-World-Firewall", "com/akashic/worldfirewall/WorldMutationGuard.class",
                    "9269a1870cbebcef633e2b7ede12b38e13ad2f6c9f6d2c5a5ec2973f6cc1730c", TODAS, "com.akashic.worldfirewall."),
            new Trava("Opis", "mcp/mobius/mobiuscore/asm/transformers/crucible/TransformerWorldCrucible.class",
                    "50db7e9b67813511f5c1103d251ef3bdcd87dc41158eddf765a253b84bdc6cb1", TODAS, "mcp.mobius."),
            new Trava("CoFHCore", "cofh/asm/ASMCore.class",
                    "acefa71392eff745dcfd6d6d5e49b6586e2e4e103d1260f4674f622152ef6f60", TODAS, "cofh.asm.", "cofh.core."),
            new Trava("RotaryCraft", "Reika/RotaryCraft/Auxiliary/EMPTileWatcher.class",
                    "5723f24de074d1a34375696f0d524cec8734985fd5458c9f0caeefb9f78995a4", TODAS, "Reika.RotaryCraft."),
            new Trava("unimixins", "io/github/legacymoddingmc/unimixins/all/AllCore.class",
                    "ad0ea4f92daf7bf7ec5c10e16258425e47929126954aea5d80df69ce26cd7318", TODAS, "io.github.legacymoddingmc.unimixins.", "org.spongepowered."),
            new Trava("EtFuturum", "ganymedes01/etfuturum/spectator/SpectatorMode.class",
                    "7a30eb666b9aa5a01895b2f7ad3723fcab68bd33a2a0b73da5d6cb335ee4c3c9", SO_MESA, "ganymedes01.etfuturum."),
            new Trava("AM2.5-LTS", "net/tclproject/mysteriumlib/asm/fixes/MysteriumPatchesFixesMagicka.class",
                    "5cdd7170b82323a2e8be4cc0018597dae2379771ea33b52fbbab0fd0dd77af32", SO_MESA, "net.tclproject.", "am2.")));

    /**
     * As classes do servidor que o caminho das classes liberadas alcanca (passo 0, secao 1; passo 2, secao 6.2), em nome
     * MCP/binario. A coleta do boot le os mixins mesclados (anotacao {@code MixinMerged}, visivel em tempo de execucao)
     * nestas classes e compara com {@link #MIXINS_COM_VEREDITO}.
     */
    public static final List<String> CLASSES_DO_CAMINHO = Collections.unmodifiableList(Arrays.asList(
            "net.minecraft.world.World", "net.minecraft.world.WorldServer", "net.minecraft.world.chunk.Chunk",
            "net.minecraft.world.chunk.storage.ExtendedBlockStorage", "net.minecraft.world.gen.ChunkProviderServer",
            "net.minecraft.tileentity.TileEntity", "net.minecraft.tileentity.TileEntityChest",
            "net.minecraft.tileentity.TileEntityEnderChest", "net.minecraft.tileentity.TileEntityEnchantmentTable"));

    /**
     * Mixins mesclados em {@link #CLASSES_DO_CAMINHO} que tem veredito (passo 0, secoes 3.1 e 3.2; os dois ultimos lidos no
     * passo 3: BugTorched {@code worldgen.MixinWorld} troca {@code getTopSolidOrLiquidBlock}, geracao; EtFuturum
     * {@code spectator.MixinWorldServer} mexe na conta de jogadores dormindo; os dois fora do caminho). Mixin mesclado fora
     * desta lista = injecao sem veredito: o recurso recusa ligar.
     */
    public static final Set<String> MIXINS_COM_VEREDITO = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            // World
            "com.akashic.neostates.mixin.early.common.MixinWorld_Water",
            "com.akashic.tickrelief.mixins.early.WorldNeighbourGuardMixin",
            "com.akashiic.bellow.mixin.early.common.MixinWorld_Deep",
            "com.akashiic.bellow.mixin.early.common.MixinWorld_DeepEntityChunk",
            "com.akashiic.bellow.mixin.early.crucible.MixinWorld_DeepCollision",
            "com.akashiic.mixer.mixins.common.MixinWorld",
            "com.falsepattern.endlessids.mixin.mixins.common.blockitem.common.WorldMixin",
            "com.mitchej123.hodgepodge.mixins.early.memory.WorldAccessor_ClearMapStorage",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorldGetBlock",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorldLightValue",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorld_BukkitSpawnReason",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorld_CoFH_TE_Cache",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorld_FastItemPhysics",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorld_FixAllocations",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorld_FixLightUpdateLag",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorld_FixXray",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorld_SpawnContext",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.shutup.MixinWorld",
            "ganymedes01.etfuturum.mixins.early.liquidphysics.MixinWorld",
            "ganymedes01.etfuturum.mixins.early.observer.MixinWorld",
            "ganymedes01.etfuturum.mixins.early.spectator.MixinWorld",
            "jss.bugtorch.mixins.early.minecraft.worldgen.MixinWorld",
            "makamys.coretweaks.mixin.tweak.lightfixstare.MixinWorld",
            "micdoodle8.mods.galacticraft.core.mixins.minecraft.WorldMixin",
            // WorldServer
            "com.akashic.saverelief.mixins.early.WorldServerMixin",
            "com.akashiic.bellow.mixin.early.common.MixinWorldServer_DeepTicks",
            "com.akashiic.bellow.mixin.early.common.MixinWorldServer_DeepTiles",
            "com.mitchej123.hodgepodge.mixins.early.memory.MixinWorldServerUpdateEntities",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorldServer_FixAllocations",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinWorldServer_LimitUpdateRecursion",
            "ganymedes01.etfuturum.mixins.early.observer.MixinWorldServer",
            "ganymedes01.etfuturum.mixins.early.playerssleepingpercentage.MixinWorldServer",
            "ganymedes01.etfuturum.mixins.early.randomtickspeed.MixinWorldServer",
            "ganymedes01.etfuturum.mixins.early.spectator.MixinWorldServer",
            // ChunkProviderServer
            "com.mitchej123.hodgepodge.mixins.early.minecraft.fastload.MixinChunkProviderServer_DeferPopulation",
            "micdoodle8.mods.galacticraft.core.mixins.minecraft.ChunkProviderServerMixin",
            // Chunk
            "cc.unilock.chromatifixes.mixin.early.perfblockplace.ChunkMixin",
            "com.akashic.neostates.mixin.early.common.MixinChunk_Water",
            "com.akashiic.bellow.mixin.early.common.MixinChunk_Deep",
            "com.akashiic.bellow.mixin.early.common.MixinChunk_DeepLightVanilla",
            "com.akashiic.bellow.mixin.early.common.MixinChunk_DeepLoad",
            "com.akashiic.bellow.mixin.early.common.MixinChunk_DeepTileEntity",
            "com.akashiic.bellow.mixin.early.common.MixinChunk_LegacyPopulate",
            "com.falsepattern.endlessids.mixin.mixins.common.biome.vanilla.ChunkMixin",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinChunk",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.MixinChunk_FixInvalidEntity",
            "com.mitchej123.hodgepodge.mixins.early.minecraft.fastload.MixinChunk_SendWithoutPopulation",
            "ganymedes01.etfuturum.mixins.early.observer.MixinChunk",
            "makamys.coretweaks.mixin.bugfix.heightmaprange.MixinChunk",
            // ExtendedBlockStorage
            "com.akashic.neostates.mixin.early.common.MixinExtendedBlockStorage_Water",
            "com.falsepattern.endlessids.mixin.mixins.common.blockitem.common.ExtendedBlockStorageMixin",
            // TileEntity
            "io.github.cruciblemc.praesidium_evolutionis.mixins.forge.tracking.MixinTileEntity")));

    /**
     * Transformadores da cadeia do class loader com veredito: os 129 da copia de teste do modpack (lidos no boot do passo
     * 3, 2026-11-27). O passo 0 varreu as constantes de todos os jars da copia: os que citam as classes do caminho tem
     * veredito proprio (T1 a T17 de passo0-vereditos.md); os outros ficam "fora do caminho". Transformador fora desta lista =
     * jar ou coremod novo, sem veredito: o recurso recusa. {@code null} = lista nao embutida (todo transformador sem veredito).
     */
    public static final Set<String> TRANSFORMADORES_COM_VEREDITO = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "cpw.mods.fml.common.asm.transformers.PatchingTransformer",
            "io.github.crucible.patches.StreamsTransformer",
            "thermos.ThermosClassTransformer",
            "sampler.asm.Transformer",
            "org.spongepowered.asm.mixin.transformer.Proxy",
            "io.github.legacymoddingmc.unimixins.compat.asm.ASMRemapperTransformer",
            "com.mitchej123.hodgepodge.core.fml.transformers.early.ModContainerFactoryTransformer",
            "cpw.mods.fml.common.asm.transformers.MarkerTransformer",
            "cpw.mods.fml.common.asm.transformers.SideTransformer$$CoreTweaksProxy",
            "cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer",
            "net.minecraftforge.classloading.FluidIdTransformer$$CoreTweaksProxy",
            "com.akashic.compat.iude.IndustrialUpgradeTransformer",
            "com.akashic.jurassicfix.JurassiCraftCapeFixTransformer",
            "net.tclproject.mysteriumlib.asm.common.FirstClassTransformer",
            "am2.preloader.BytecodeTransformers",
            "airburn.am2playground.preloader.PGClassTransformer",
            "appeng.transformer.asm.ASMIntegration",
            "appeng.transformer.asm.ApiRepairer",
            "appeng.transformer.asm.GuiButtonColorizer",
            "codechicken.lib.asm.ClassHeirachyManager",
            "codechicken.lib.asm.RedirectorTransformer",
            "codechicken.core.asm.TweakTransformer",
            "codechicken.nei.asm.NEITransformer",
            "li.cil.oc.common.asm.ClassTransformer",
            "mcp.mobius.mobiuscore.asm.CoreTransformer",
            "api.player.forge.PlayerAPITransformer",
            "io.github.legacymoddingmc.unimixins.compat.asm.EnhanceCrashReportsTransformer",
            "makamys.coretweaks.asm.ModDiscovererTransformer",
            "makamys.coretweaks.asm.FMLLogTransformer",
            "com.falsepattern.endlessids.asm.EndlessIDsTransformer",
            "micdoodle8.mods.galacticraft.core.asm.GCTransformer",
            "com.gtnewhorizon.gtnhlib.core.fml.transformers.EventBusSubTransformer",
            "com.gtnewhorizon.gtnhlib.core.fml.transformers.BlockIconTransformer",
            "com.mitchej123.hodgepodge.core.fml.transformers.mc.VarargDissector",
            "com.mitchej123.hodgepodge.core.fml.transformers.fml.FMLIndexedMessageToMessageCodecTransformer",
            "com.mitchej123.hodgepodge.core.fml.transformers.fml.ModAPITransformerSignaturePatch",
            "com.mitchej123.hodgepodge.core.fml.transformers.thermos.ThermosFurnaceSledgeHammer",
            "com.cleanroommc.modularui.core.ModularUITransformer",
            "dev.rndmorris.salisarcana.core.asm.SalisArcanaClassTransformer",
            "net.kohi.tcpnodelaymod.ClassTransformer",
            "openmods.core.OpenModsClassTransformer",
            "openblocks.asm.OpenBlocksClassTransformer",
            "cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer$$CoreTweaksProxy",
            "cpw.mods.fml.common.asm.transformers.AccessTransformer",
            "net.minecraftforge.transformers.ForgeAccessTransformer",
            "appeng.transformer.asm.ASMTweaker",
            "io.github.legacymoddingmc.unimixins.all.AllCore$CombinedAccessTransformer",
            "com.mitchej123.hodgepodge.core.CoFHCoreAccessTransformer",
            "cpw.mods.fml.common.asm.transformers.ModAccessTransformer",
            "cpw.mods.fml.common.asm.transformers.ItemStackTransformer",
            "net.tclproject.mysteriumlib.asm.common.CustomClassTransformer",
            "br.com.redeakashic.ae2ic2fix.AE2PowerSinkTransformer",
            "com.akashic.elnendlessids.ElnEndlessIdsTransformer",
            "com.akashic.journeymapfix.JourneyMapTrackingTransformer",
            "com.akashic.journeymapfix.JourneyMapChunkOverlayTransformer",
            "br.com.redeakashic.orespawnentityfix.core.EntityFixTransformer",
            "com.chocohead.ccc.CarefulCastCorrector",
            "Reika.ChromatiCraft.Auxiliary.ChromaASMHandler$ASMExecutor",
            "cofh.asm.CoFHClassTransformer",
            "Reika.DragonAPI.ASM.InterfaceInjector",
            "Reika.DragonAPI.ASM.APIStripper",
            "Reika.DragonAPI.ASM.DragonAPIClassTransformer",
            "Reika.DragonAPI.ASM.FMLItemBlockPatch",
            "Reika.DragonAPI.ASM.FluidNamePatch",
            "Reika.DragonAPI.ASM.DependentMethodStripper",
            "Reika.DragonAPI.ASM.ClassReparenter",
            "Reika.DragonAPI.ASM.Profiling.WorldGeneratorProfilingHooks",
            "Reika.DragonAPI.ASM.Profiling.MapGeneratorProfilingHooks",
            "Reika.DragonAPI.ASM.Profiling.ChunkInitProfilingHooks",
            "Reika.DragonAPI.ASM.StructureLootHooks",
            "Reika.DragonAPI.ASM.SpecialBiomePlacement",
            "pl.asie.foamfix.bugfixmod.coremod.BugfixModClassTransformer",
            "flaxbeard.immersivepetroleum.common.compat.drilloil.asm.PetroleumDrillTransformer",
            "flaxbeard.immersivepetroleum.common.compat.drilloil.asm.PetroleumPotionIdTransformer",
            "flaxbeard.immersivepetroleum.common.compat.drilloil.asm.PetroleumRefineryTransformer",
            "net.ilexiconn.llibrary.server.core.plugin.LLibraryTransformer",
            "net.ilexiconn.llibrary.server.core.patcher.LLibraryRuntimePatcher",
            "com.gamerforea.packetunlimiter.asm.ASMTransformer",
            "Reika.RotaryCraft.Auxiliary.RotaryASMHandler$ASMExecutor",
            "Reika.RotaryCraft.Auxiliary.RotaryASMHandler$InterfaceVerifier",
            "com.akashic.baublesexpandedapifix.InterfaceRepairTransformer",
            "com.akashic.dimlogin.core.LoginTransformer",
            "com.akashic.mekthrottle.asm.MekThrottleTransformer",
            "com.akashic.tickrelief.core.AM2PGRegenFixTransformer",
            "br.com.redeakashic.orespawnguard.core.GuardTransformer",
            "br.com.redeakashic.orespawn.spawn.core.SpawnTransformer",
            "com.falsepattern.lib.internal.asm.FPTransformer",
            "net.glease.tc4tweak.asm.TC4Transformer",
            "br.com.redeakashic.integrityfix.IntegrityTransformer",
            "org.blockartistry.mod.DynSurround.asm.Transformer",
            "net.laurus.nettyfix.asm.NP_CORE_Handler",
            "com.akashic.chromasoundhashfix.core.WorldClientVerifierRepairTransformer",
            "com.akashic.compat.dragonapichunkgen.ChunkGenerationRuntimeRepairTransformer",
            "com.akashic.compat.worldgenframe.WorldGenDungeonsFrameTransformer",
            "com.akashic.compat.bukkitendlessids.v102.CustomChunkGeneratorTransformer",
            "com.akashic.compat.endermanframe.EndermanFrameTransformer",
            "com.akashic.compat.chromatifixesserver.ChromatiFixesLateLoaderTransformer",
            "com.akashic.compat.dhendlessids.DHEndlessIDsCompatTransformer",
            "com.akashic.worldfirewall.WorldSetBlockTransformer",
            "com.akashic.worldfirewall.PassiveItemTransformer",
            "com.akashic.worldfirewall.RuntimeTickTransformer",
            "com.akashic.worldfirewall.EquipmentTransformer",
            "com.akashic.worldfirewall.InventoryOutputTransformer",
            "com.akashic.worldfirewall.NetworkPacketTransformer",
            "dev.akashic.playerbackport.core.PlayerCollisionTransformer",
            "dev.akashic.playerbackport.core.PlayerEdgeSneakTransformer",
            "dev.akashic.playerbackport.core.PlayerBodyTurnTransformer",
            "dev.akashic.playerbackport.core.PlayerCameraTransformer",
            "dev.akashic.playerbackport.core.RemoteHeadPacketTransformer",
            "dev.akashic.playerbackport.core.RemotePlayerHeadTickTransformer",
            "dev.akashic.playerbackport.core.RemoteVelocityPacketTransformer",
            "dev.akashic.playerbackport.core.RemotePlayerVelocityTickTransformer",
            "dev.akashic.playerbackport.core.PlayerRayTraceOriginTransformer",
            "dev.akashic.playerbackport.core.PlayerMouseOverOriginTransformer",
            "dev.akashic.playerbackport.core.PlayerCrawlingRenderTransformer",
            "dev.akashic.playerbackport.core.PlayerCrawlingMovementTransformer",
            "dev.akashic.playerbackport.core.PlayerCrawlingModelTransformer",
            "dev.akashic.playerbackport.core.PlayerFirstPersonArmTransformer",
            "dev.akashic.playerbackport.core.PlayerCapePoseTransformer",
            "dev.akashic.playerbackport.core.PlayerCrouchingGroundingTransformer",
            "dev.akashic.playerbackport.core.PlayerFallFlyingModelTransformer",
            "dev.akashic.playerbackport.core.PlayerModernArmPoseTransformer",
            "dev.akashic.playerbackport.core.PlayerModernRigTransformer",
            "dev.akashic.playerbackport.core.PlayerSwimmingAnimationTransformer",
            "dev.akashic.playerbackport.core.PlayerSwimmingMovementTransformer",
            "dev.akashic.playerbackport.core.PlayerSwimmingVerticalTransformer",
            "com.akashic.compat.dhrejectedlogout.RejectedLogoutGuardTransformer",
            "cpw.mods.fml.common.asm.transformers.TerminalTransformer$$CoreTweaksProxy",
            "cpw.mods.fml.common.asm.transformers.ModAPITransformer")));

    /** Observadores revisados do {@code ChunkRequestEvent} do DragonAPI (passo 0, T6). */
    public static final Set<String> OBSERVADORES_REVISADOS = Collections.unmodifiableSet(new HashSet<String>(
            Collections.singletonList("Reika.DragonAPI.Auxiliary.WorldGenInterceptionRegistry")));

    /** Watchers revisados do {@code TileUpdateEvent} do DragonAPI (passo 0, T5; decisao 39c). */
    public static final Set<String> WATCHERS_REVISADOS = Collections.unmodifiableSet(new HashSet<String>(
            Collections.singletonList("Reika.RotaryCraft.Auxiliary.EMPTileWatcher")));

    /** A classe do {@code world.rand} do servidor (o construtor de World faz {@code new ThermiteRandom}). */
    public static final String CLASSE_RAND = "thermos.thermite.ThermiteRandom";

    /** Classes do jogo e dos mods que a coleta procura (sem carregar: por recurso). */
    public static final String RECURSO_HODGEPODGE = "com/mitchej123/hodgepodge/config/FixesConfig.class";
    public static final String CLASSE_HODGEPODGE = "com.mitchej123.hodgepodge.config.FixesConfig";
    public static final String RECURSO_DRAGONAPI = "Reika/DragonAPI/Instantiable/Event/TileUpdateEvent.class";
    public static final String CLASSE_CHUNK_REQUEST = "Reika.DragonAPI.Instantiable.Event.ChunkRequestEvent";
    public static final String CLASSE_TILE_UPDATE = "Reika.DragonAPI.Instantiable.Event.TileUpdateEvent";
    public static final String CLASSE_PROFILER_GERACAO = "Reika.DragonAPI.Auxiliary.Trackers.WorldgenProfiler";
    public static final String RECURSO_FIREWALL = "com/akashic/worldfirewall/WorldMutationGuard.class";
    public static final String CLASSE_FIREWALL = "com.akashic.worldfirewall.WorldMutationGuard";
}
