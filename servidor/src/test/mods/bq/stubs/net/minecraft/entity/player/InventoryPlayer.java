// Hoshiestar - stub de TESTE (so para o teste isolado do filtro do BetterQuesting; nunca vai para o jar):
// so os membros, com nomes SRG, que as classes reais do BQ e o filtro usam (lista tirada do constant pool).
package net.minecraft.entity.player;
import net.minecraft.item.ItemStack;
public class InventoryPlayer { public ItemStack[] slots=new ItemStack[40]; public int writes;
  public int func_70302_i_(){return slots.length;}
  public ItemStack func_70301_a(int i){return slots[i];}
  public ItemStack func_70298_a(int i,int n){ writes++; ItemStack s=slots[i]; if(s==null) return null;
    if(s.field_77994_a<=n){slots[i]=null;return s;} return s.func_77979_a(n);} }
