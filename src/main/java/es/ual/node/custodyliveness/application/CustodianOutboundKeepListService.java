package es.ual.node.custodyliveness.application;

import es.ual.node.custodyliveness.domain.CustodyProbeDirection;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.domain.CustodyProbeStatus;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInventoryPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import es.ual.node.custodyliveness.ports.out.RemoteOriginKeepListClientPort;
import es.ual.node.custodyliveness.ports.out.RemoteOriginKeepListClientPort.OriginKeepListResult;
import es.ual.node.custodyliveness.ports.out.RemoteOriginKeepListClientPort.RemoteOriginKeepListException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custodian-side service que inicia el probe periódico al origen.
 *
 * <p>Para cada {@code requesterNodeId} con fragments custodiados localmente:
 *
 * <ol>
 *   <li>Lista los fragments locales del requester via {@link CustodyFragmentInventoryPort}.
 *   <li>Resuelve {@code originBaseUrl} via {@code node.custody-liveness.remote-base-urls}.
 *   <li>Invoca {@code POST /ops/custody-liveness/keep-list-request} firmado.
 *   <li>Recibe whitelist; calcula diff con la lista local.
 *   <li>Para cada fragment NO en whitelist: invoca {@link
 *       CustodyFragmentLifecyclePort#decommissionCustody(String)} (hard-delete + cancel agreement).
 * </ol>
 *
 * <p>Si el origen no responde, NO se purga nada, el custodian asume que el origen está
 * temporalmente inactivo. El probe inverso del origen (origen → custodian) detectará la situación
 * independientemente y aplicará política de integridad. Aquí sólo emitimos WARN.
 */
public class CustodianOutboundKeepListService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CustodianOutboundKeepListService.class);

  private final CustodyFragmentInventoryPort inventoryPort;
  private final CustodyFragmentLifecyclePort lifecyclePort;
  private final RemoteOriginKeepListClientPort keepListClient;
  private final CustodyLivenessProperties livenessProperties;
  private final CustodyProbeSessionPort sessionPort;
  private final Clock clock;

  /** Creates service. */
  public CustodianOutboundKeepListService(
      final CustodyFragmentInventoryPort inventoryPort,
      final CustodyFragmentLifecyclePort lifecyclePort,
      final RemoteOriginKeepListClientPort keepListClient,
      final CustodyLivenessProperties livenessProperties,
      final CustodyProbeSessionPort sessionPort,
      final Clock clock) {
    if (inventoryPort == null
        || lifecyclePort == null
        || keepListClient == null
        || livenessProperties == null
        || sessionPort == null
        || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.inventoryPort = inventoryPort;
    this.lifecyclePort = lifecyclePort;
    this.keepListClient = keepListClient;
    this.livenessProperties = livenessProperties;
    this.sessionPort = sessionPort;
    this.clock = clock;
  }

  /**
   * Ejecuta un ciclo completo: itera por requester, envía probe, purga fragments no whitelisted.
   *
   * @return resumen de resultados (probesSent, totalPurged, requesterErrors)
   */
  public CycleSummary runOnce() {
    int probesSent = 0;
    int totalPurged = 0;
    int requesterErrors = 0;
    final Instant now = Instant.now(clock);
    final Map<String, String> baseUrls = livenessProperties.getRemoteBaseUrls();

    for (String requesterNodeId : inventoryPort.listDistinctRequesterNodeIds()) {
      final String originBaseUrl = baseUrls.get(requesterNodeId);
      if (originBaseUrl == null || originBaseUrl.isBlank()) {
        LOGGER
            .atWarn()
            .setMessage(
                "Custodian probe outbound: requester baseUrl not configured (skipping). Configure"
                    + " node.custody-liveness.remote-base-urls.")
            .addKeyValue("event", "CUSTODY_LIVENESS_REQUESTER_BASEURL_MISSING")
            .addKeyValue("requesterNodeId", requesterNodeId)
            .log();
        requesterErrors++;
        continue;
      }
      final List<CustodyProbeFragment> mine =
          inventoryPort.findCustodiedForRequester(requesterNodeId, now);
      final List<String> myFragmentIds =
          mine.stream().map(CustodyProbeFragment::fragmentId).toList();
      if (myFragmentIds.isEmpty()) {
        continue;
      }
      try {
        final OriginKeepListResult result =
            keepListClient.requestKeepList(originBaseUrl, requesterNodeId, myFragmentIds);
        probesSent++;
        final List<String> keepList = result.keepFragmentIds();
        // Aprende/refresca el tutor que el origen anuncia (propaga si cambia); si el origen
        // devuelve lista vacía ya no custodia nada para él → purga el hint.
        if (keepList.isEmpty()) {
          clearOriginTutorHint(requesterNodeId);
        } else {
          upsertOriginTutorHint(requesterNodeId, result.tutorBaseUrl(), now);
        }
        final Set<String> keepSet = new HashSet<>(keepList);
        final long renewHorizon = Math.max(1L, livenessProperties.getRenewalHorizonSeconds());
        final long renewBy = Math.max(1L, livenessProperties.getRenewalSeconds());
        for (String fragmentId : myFragmentIds) {
          if (keepSet.contains(fragmentId)) {
            // El origen confirma que sigue queriendo este fragment → renovar TTL si el
            // fragment está expirado o dentro del horizon. Esto es ruta de redundancia
            // sobre la renovación del direct probe: si esa ruta está rota (e.g. self-loop
            // con TTL ya caducado bloqueando inventory), el keep-list la cura.
            lifecyclePort
                .findByFragmentId(fragmentId)
                .ifPresent(
                    stored -> {
                      final long secondsToExpiry =
                          stored.expiresAt().getEpochSecond() - now.getEpochSecond();
                      if (secondsToExpiry < renewHorizon) {
                        lifecyclePort.extendCustody(fragmentId, renewBy);
                      }
                    });
          } else {
            try {
              lifecyclePort.decommissionCustody(fragmentId);
              totalPurged++;
              LOGGER
                  .atInfo()
                  .setMessage("Custodian purged fragment per origin keep-list")
                  .addKeyValue("event", "CUSTODY_LIVENESS_PURGED_FRAGMENT")
                  .addKeyValue("requesterNodeId", requesterNodeId)
                  .addKeyValue("fragmentId", fragmentId)
                  .log();
            } catch (RuntimeException ex) {
              LOGGER
                  .atWarn()
                  .setMessage("Custodian failed to purge fragment (skipping)")
                  .addKeyValue("event", "CUSTODY_LIVENESS_PURGE_FAILED")
                  .addKeyValue("requesterNodeId", requesterNodeId)
                  .addKeyValue("fragmentId", fragmentId)
                  .addKeyValue("error", ex.getMessage())
                  .log();
            }
          }
        }
      } catch (RemoteOriginKeepListException ex) {
        requesterErrors++;
        LOGGER
            .atWarn()
            .setMessage(
                "Custodian probe outbound failed; not purging anything (origin may be temporarily"
                    + " down)")
            .addKeyValue("event", "CUSTODY_LIVENESS_PROBE_FAILED")
            .addKeyValue("severity", "medium")
            .addKeyValue("requesterNodeId", requesterNodeId)
            .addKeyValue("originBaseUrl", originBaseUrl)
            .addKeyValue("error", ex.getMessage())
            .log();
      }
    }
    return new CycleSummary(probesSent, totalPurged, requesterErrors);
  }

  /**
   * Aprende/refresca el tutor anunciado por el origen, vinculado al {@code requesterNodeId}, en una
   * sesión-hint inerte ({@code next_attempt_at=null} → los workers de sonda la ignoran).
   * Idempotente: no reescribe si el tutor no cambió.
   */
  private void upsertOriginTutorHint(
      final String requesterNodeId, final String tutorBaseUrl, final Instant now) {
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      return;
    }
    final String trimmed = tutorBaseUrl.trim();
    final String id = CustodyProbeSession.ORIGIN_TUTOR_HINT_PREFIX + requesterNodeId;
    final var existing = sessionPort.findById(id);
    if (existing.isPresent() && trimmed.equals(existing.get().remoteTutorBaseUrl())) {
      return;
    }
    final Instant createdAt = existing.map(CustodyProbeSession::createdAt).orElse(now);
    sessionPort.save(
        new CustodyProbeSession(
            id,
            requesterNodeId,
            CustodyProbeDirection.OUTBOUND,
            CustodyProbeStatus.ACTIVE,
            0,
            now,
            now,
            null,
            null,
            null,
            createdAt,
            now,
            trimmed));
  }

  /** Limpia el tutor-hint del origen (origen relinquished todos sus fragments). */
  private void clearOriginTutorHint(final String requesterNodeId) {
    final String id = CustodyProbeSession.ORIGIN_TUTOR_HINT_PREFIX + requesterNodeId;
    sessionPort
        .findById(id)
        .filter(s -> s.remoteTutorBaseUrl() != null)
        .ifPresent(
            s ->
                sessionPort.save(
                    new CustodyProbeSession(
                        s.sessionId(),
                        s.remoteNodeId(),
                        s.direction(),
                        s.status(),
                        s.attemptCount(),
                        s.lastSuccessAt(),
                        s.lastAttemptAt(),
                        s.nextAttemptAt(),
                        s.lastError(),
                        s.reverseProbeCooldownUntil(),
                        s.createdAt(),
                        Instant.now(clock),
                        null)));
  }

  /** Resumen de un ciclo de probes outbound. */
  public record CycleSummary(int probesSent, int totalPurged, int requesterErrors) {}
}
