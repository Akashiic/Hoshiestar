// Hoshiestar - stub de TESTE (so para o teste isolado do recurso 1.F, Ars Magica 2; nunca vai para o jar):
// so os membros, com nomes SRG, que o AMEventHandler real executa nos dois metodos testados.
package am2.buffs;
/** Ponto de parada do teste: e a primeira coisa que o onEntityLiving real chama DEPOIS do trecho do DiluteTime (offset 77..81). */
public class BuffStatModifiers {
  public static final BuffStatModifiers instance = new BuffStatModifiers();
  public static long alcancadas;
  public void applyStatModifiersBasedOnBuffs(net.minecraft.entity.EntityLivingBase e) { alcancadas++; throw new Alcancou72(); }
}
