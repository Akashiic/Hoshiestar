// Hoshiestar - stub de TESTE (so para o teste isolado do filtro do BetterQuesting; nunca vai para o jar):
// so os membros, com nomes SRG, que as classes reais do BQ e o filtro usam (lista tirada do constant pool).
package net.minecraft.entity.player;
import net.minecraftforge.common.IExtendedEntityProperties;
public class EntityPlayer { public InventoryPlayer field_71071_by; public int extPropCalls;
  public IExtendedEntityProperties getExtendedProperties(String k){extPropCalls++;return null;} }
