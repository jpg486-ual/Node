package es.ual.node.filesystem.domain;

/**
 * Estado de salud de un {@link FragmentPlacement} desde la vista del nodo origen.
 *
 * <p>Persistido en {@code client_fragment_placement.health_status}. Las transiciones entre estados
 * son operadas por:
 *
 * <ul>
 *   <li>El handler de inbound probe iniciado por el custodian ({@code POST
 *       /ops/custody-liveness/keep-list-request}) confirma {@link #OK} para los fragments listados.
 *   <li>El worker de probe inverso (origen → custodian) cuando el custodian deja de iniciar probes
 *       en el plazo configurado: 1ª no-respuesta marca {@link #EN_RIESGO} (warn); tras N intentos
 *       consecutivos sin respuesta marca {@link #PERDIDO} (warn grave). Si custodian responde
 *       inventario y faltan fragments esperados → {@link #PERDIDO} directo.
 * </ul>
 *
 * <p>{@link #PERDIDO} es transición terminal salvo recompose total del archivo: aunque el custodian
 * vuelva online, el placement permanece {@code PERDIDO} porque la política de integridad
 * (FileIntegrityRiskOrchestrator) ya tomó decisiones basadas en ese estado. La única forma de
 * "salir" de {@code PERDIDO} es que el orquestador dispare un re-upload total y se regenere todo el
 * manifest con un fileId nuevo.
 */
public enum FragmentHealthStatus {

  /** Custodian confirmado vivo y conservando el fragment en su última probe. */
  OK,

  /**
   * Custodian no respondió la 1ª probe inversa después del plazo {@code interval + tolerance}. El
   * fragment está sin verificar y emite WARN, pero todavía es considerado para reconstrucción si
   * los demás están OK. Estado intermedio entre {@link #OK} y {@link #PERDIDO}.
   */
  EN_RIESGO,

  /**
   * Custodian se considera caído tras {@code unresponsive-threshold-attempts} intentos consecutivos
   * sin respuesta, o respondió pero confirmó NO tener el fragment. Estado terminal (irrevocable
   * salvo recompose total). El fragment cuenta como contribución máxima al risk score del archivo.
   */
  PERDIDO
}
