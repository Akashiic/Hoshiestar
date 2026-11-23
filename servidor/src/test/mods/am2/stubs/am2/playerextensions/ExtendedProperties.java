// Hoshiestar - stub de TESTE (so para o teste isolado do recurso 1.F, Ars Magica 2; nunca vai para o jar):
// so os membros, com nomes SRG, que o AMEventHandler real executa nos dois metodos testados.
package am2.playerextensions;
import java.util.*;
public class ExtendedProperties {
  public boolean hadFlight;
  public final HashMap<String,String> extra_properties = new HashMap<String,String>();
  public void addToExtraVariables(String n, String v) { extra_properties.put(n, v); }
  public void removeFromExtraVariables(String n) { extra_properties.remove(n); }
  /** Como o real: copia as chaves que contem o texto para um mapa novo (o chamador mexe nele e o esvazia). */
  public Map<String,String> getExtraVariablesContains(String c) {
    HashMap<String,String> r = new HashMap<String,String>();
    for (String k : extra_properties.keySet()) if (k.contains(c)) r.put(k, extra_properties.get(k));
    return r; }
  public String getExtraVariable(String n) { return extra_properties.get(n); }
  public boolean hasExtraVariable(String n) { return extra_properties.get(n) != null; }
}
