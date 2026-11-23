// Hoshiestar - stub de TESTE (so para o teste isolado do filtro do BetterQuesting; nunca vai para o jar):
// so os membros, com nomes SRG, que as classes reais do BQ e o filtro usam (lista tirada do constant pool).
package net.minecraftforge.oredict;
import java.util.*; import net.minecraft.item.ItemStack;
public class OreDictionary { public static final Map<String,ArrayList<ItemStack>> ORES=new HashMap<String,ArrayList<ItemStack>>();
  public static ArrayList<ItemStack> getOres(String n){ArrayList<ItemStack> l=ORES.get(n); if(l==null){l=new ArrayList<ItemStack>();ORES.put(n,l);} return l;}
  public static int[] getOreIDs(ItemStack s){return new int[0];}
  public static boolean itemMatches(ItemStack target, ItemStack input, boolean strict){
    if (input == null && target != null || input != null && target == null) return false;
    return (target.func_77973_b() == input.func_77973_b() && ((target.func_77960_j() == 32767 && !strict) || target.func_77960_j() == input.func_77960_j())); } }
