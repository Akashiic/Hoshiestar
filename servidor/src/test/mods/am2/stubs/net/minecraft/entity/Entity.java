// Hoshiestar - stub de TESTE (so para o teste isolado do recurso 1.F, Ars Magica 2; nunca vai para o jar):
// so os membros, com nomes SRG, que o AMEventHandler real executa nos dois metodos testados.
package net.minecraft.entity;
import java.util.UUID;
public class Entity {
  public net.minecraft.world.World field_70170_p;
  public int field_70173_aa;                        // ticksExisted
  public double field_70159_w, field_70181_x, field_70179_y;
  public UUID uuid = UUID.randomUUID();
  public UUID func_110124_au() { return uuid; }
  public int updates;                               // teste: conta as chamadas de func_70071_h_ (as "atualizacoes extras" do Accelerate)
  public Runnable gancho;                           // teste: algo que acontece no meio da atualizacao (ex.: mexer no mapa do AM2)
  public void func_70071_h_() { updates++; if (gancho != null) gancho.run(); }
}
