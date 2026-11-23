// Hoshiestar - stub de TESTE (so para o teste isolado do recurso 1.F, Ars Magica 2; nunca vai para o jar):
// so os membros, com nomes SRG, que o AMEventHandler real executa nos dois metodos testados.
package am2.spell;
import am2.api.spell.component.interfaces.ISkillTreeEntry;
public class SkillTreeManager {
  public static final SkillTreeManager instance = new SkillTreeManager();
  public static boolean desativada;                 // teste: a habilidade registrada esta desativada na config do AM2
  public static long chamadas;
  /** Como o real: entrada nula nunca esta desativada (getSkillTreeEntry(null) devolve null). */
  public boolean isSkillDisabled(ISkillTreeEntry e) { chamadas++; return e != null && desativada; }
}
