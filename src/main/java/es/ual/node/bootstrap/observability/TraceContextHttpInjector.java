package es.ual.node.bootstrap.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.net.http.HttpRequest;
import org.springframework.stereotype.Component;

/**
 * helper centralizado para inyectar W3C Trace Context en {@link HttpRequest.Builder}.
 *
 * <p>Los 3 adapters HTTP outbound del Node (custody probe, tutor return, fragment transfer) usan
 * {@code java.net.http.HttpClient} ad-hoc (no son beans Spring), por lo que la auto-instrumentación
 * Spring Boot/Micrometer no los cubre. Este componente delega en el {@link TextMapPropagator}
 * configurado por OpenTelemetry (default W3C en Spring Boot 3.5.x) para añadir las cabeceras {@code
 * traceparent} y opcionalmente {@code tracestate} al builder, leyendo el span actual del {@link
 * Context#current()}.
 *
 * <p>Invariantes:
 *
 * <ul>
 *   <li>Los headers W3C se añaden DESPUÉS de la construcción del canonical payload de firma,
 *       confirmar mirando los 3 callers: el canonical payload de {@code
 *       RequestSignatureValidationService.buildCanonicalPayload} se calcula con (method, path,
 *       body, nonce, timestamp) y NO incluye headers HTTP, por lo que esta inyección no rompe la
 *       firma.
 *   <li>Si no hay span activo en el contexto actual, {@link TextMapPropagator#inject} es no-op (no
 *       añade cabeceras), lo cual permite ejecutar los 3 clients fuera de un scope traceable sin
 *       errores.
 * </ul>
 */
@Component
public class TraceContextHttpInjector {

  private static final TextMapSetter<HttpRequest.Builder> BUILDER_SETTER =
      (carrier, key, value) -> {
        if (carrier != null && key != null && value != null) {
          carrier.header(key, value);
        }
      };

  private final TextMapPropagator propagator;

  /** Creates injector. */
  public TraceContextHttpInjector(final OpenTelemetry openTelemetry) {
    if (openTelemetry == null) {
      throw new IllegalArgumentException("openTelemetry must not be null");
    }
    this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
  }

  /**
   * Inyecta los headers de propagación W3C Trace Context (typicamente {@code traceparent}, y {@code
   * tracestate} cuando aplica) sobre el builder.
   *
   * @param builder builder de la request outbound; no debe ser null.
   */
  public void inject(final HttpRequest.Builder builder) {
    if (builder == null) {
      throw new IllegalArgumentException("builder must not be null");
    }
    propagator.inject(Context.current(), builder, BUILDER_SETTER);
  }
}
