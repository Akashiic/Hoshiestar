/*
 * Hoshiestar-Medidor: mede onde, no mapa, vai o tempo de tick.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo
 * sob os termos da GNU General Public License publicada pela Free Software
 * Foundation, versao 3 da Licenca ou (a seu criterio) qualquer versao posterior.
 *
 * Este programa e distribuido na esperanca de que seja util, mas SEM QUALQUER
 * GARANTIA; sem mesmo a garantia implicita de COMERCIABILIDADE ou ADEQUACAO A
 * UM PROPOSITO ESPECIFICO. Veja a GNU General Public License para mais detalhes.
 *
 * Voce deve ter recebido uma copia da GNU General Public License junto com este
 * programa. Se nao, veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.medidor.core;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import hoshiestar.medidor.Ganchos;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coremod minimo: so existe para entregar a lista de mixins "early" ao GTNHMixins
 * (UniMixins). Nao registra transformador ASM nenhum.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("HoshiestarMedidorCore")
public class MedidorCore implements IFMLLoadingPlugin, IEarlyMixinLoader {

    private static final Logger LOG = Ganchos.LOG;

    @Override
    public String getMixinConfig() {
        return "mixins.hoshiestar_medidor.early.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        boolean firewall = false;
        try {
            if (loadedCoreMods != null) {
                for (String s : loadedCoreMods) {
                    if (s != null && s.toLowerCase().contains("worldfirewall")) {
                        firewall = true;
                    }
                }
            }
            ClassLoader cl = MedidorCore.class.getClassLoader();
            if (cl != null && cl.getResource("com/akashic/worldfirewall/WorldMutationGuard.class") != null) {
                firewall = true;
            }
        } catch (Throwable t) {
            // so diagnostico; nunca impede o boot
        }
        Ganchos.firewallInstalado = firewall;
        LOG.info("[Hoshiestar-Medidor] Mixins early: MixinWorld, MixinEntityPlayerMP (Akashic-World-Firewall "
                + (firewall ? "presente" : "ausente") + "). Medicao desligada ate o comando 'hoshie medir'.");
        List<String> mixins = new ArrayList<String>();
        mixins.add("MixinWorld");
        mixins.add("MixinEntityPlayerMP");
        return mixins;
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
