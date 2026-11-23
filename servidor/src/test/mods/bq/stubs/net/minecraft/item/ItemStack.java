// Hoshiestar - stub de TESTE (so para o teste isolado do filtro do BetterQuesting; nunca vai para o jar):
// so os membros, com nomes SRG, que as classes reais do BQ e o filtro usam (lista tirada do constant pool).
package net.minecraft.item;
import net.minecraft.nbt.NBTTagCompound;
public class ItemStack { public int field_77994_a; private Item item; private int damage; private NBTTagCompound tag;
  public ItemStack(Item i,int size,int dmg){item=i;field_77994_a=size;damage=dmg;}
  public Item func_77973_b(){return item;}
  public int func_77960_j(){return damage;}
  public void func_77964_b(int d){damage=d;}
  public NBTTagCompound func_77978_p(){return tag;}
  public void func_77982_d(NBTTagCompound t){tag=t;}
  public boolean func_77942_o(){return tag!=null;}
  public int func_77976_d(){return 64;}
  public ItemStack func_77946_l(){ItemStack s=new ItemStack(item,field_77994_a,damage);s.tag=tag;return s;}
  public ItemStack func_77979_a(int n){ItemStack s=new ItemStack(item,n,damage);s.tag=tag;field_77994_a-=n;return s;}
  public String func_82833_r(){return String.valueOf(item);}
  public String toString(){return field_77994_a+"x"+item+"@"+damage;} }
