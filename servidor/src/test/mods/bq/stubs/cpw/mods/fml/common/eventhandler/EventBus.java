// Hoshiestar - stub de TESTE (so para o teste isolado do filtro do BetterQuesting; nunca vai para o jar):
// so os membros, com nomes SRG, que as classes reais do BQ e o filtro usam (lista tirada do constant pool).
package cpw.mods.fml.common.eventhandler;
public class EventBus { public final java.util.List<Event> posted=new java.util.ArrayList<Event>(); public boolean post(Event e){posted.add(e);return false;} }
