// Hoshiestar - stub de TESTE (so para o teste isolado do recurso 1.F, Ars Magica 2; nunca vai para o jar):
// so os membros, com nomes SRG, que o AMEventHandler real executa nos dois metodos testados.
package am2.spell;
import am2.api.spell.component.interfaces.ISkillTreeEntry;
public class SkillManager {
  public static SkillManager instance = new SkillManager();
  public static final ISkillTreeEntry ENTRADA = new ISkillTreeEntry() {};
  public static boolean registrada = true;          // teste: false = getSkill devolve null (habilidade nao registrada)
  public static long chamadas;                      // teste: conta as chamadas (de qualquer ponto do AMEventHandler)
  public ISkillTreeEntry getSkill(String n) { chamadas++; return registrada ? ENTRADA : null; }
}
