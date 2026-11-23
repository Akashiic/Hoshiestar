package Reika.VoidMonster.Auxiliary;

/**
 * Cola de teste: no servidor real o FML tira esta interface de {@code EntityMonsterBait} quando o mod VoidMonster nao
 * existe (ModAPITransformer). O teste isolado nao roda o FML, e o verificador da JVM precisa carregar
 * {@code EntityMonsterBait} ao verificar {@code AbilityCalls}; sem a interface a carga falha.
 */
public interface VoidMonsterBait {
}
