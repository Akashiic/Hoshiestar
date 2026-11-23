// Hoshiestar - stub de TESTE (so para o teste isolado do recurso 1.F, Ars Magica 2; nunca vai para o jar):
// so os membros, com nomes SRG, que o AMEventHandler real executa nos dois metodos testados.
package net.minecraftforge.event.entity.living;
import net.minecraft.entity.EntityLivingBase;
public class LivingEvent extends cpw.mods.fml.common.eventhandler.Event {
  public final EntityLivingBase entityLiving;
  public LivingEvent(EntityLivingBase e) { entityLiving = e; }
  public static class LivingUpdateEvent extends LivingEvent {
    public LivingUpdateEvent(EntityLivingBase e) { super(e); }
  }
}
