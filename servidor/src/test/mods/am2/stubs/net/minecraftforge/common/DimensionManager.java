// Hoshiestar - stub de TESTE (so para o teste isolado do recurso 1.F, Ars Magica 2; nunca vai para o jar):
// so os membros, com nomes SRG, que o AMEventHandler real executa nos dois metodos testados.
package net.minecraftforge.common;
import net.minecraft.world.WorldServer;
public class DimensionManager {
  public static WorldServer[] worlds = new WorldServer[0];
  public static long calls;                         // teste: quantas vezes o AM2 pediu a lista de mundos (o original e o que conta)
  public static WorldServer[] getWorlds() { calls++; return worlds.clone(); }
  public static WorldServer getWorld(int id) { return null; }
}
