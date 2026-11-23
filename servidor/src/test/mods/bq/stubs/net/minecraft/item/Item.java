// Hoshiestar - stub de TESTE (so para o teste isolado do filtro do BetterQuesting; nunca vai para o jar):
// so os membros, com nomes SRG, que as classes reais do BQ e o filtro usam (lista tirada do constant pool).
package net.minecraft.item;
public class Item { public final String name; public final boolean damageable;
  public Item(String n, boolean d){name=n;damageable=d;}
  public boolean func_77645_m(){return damageable;}
  public String toString(){return name;} }
