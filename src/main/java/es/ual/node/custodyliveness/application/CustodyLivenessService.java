package es.ual.node.custodyliveness.application;

import es.ual.node.custodyliveness.domain.CustodyEscalationPolicy;
import es.ual.node.custodyliveness.domain.CustodyProbeDirection;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeRequest;
import es.ual.node.custodyliveness.domain.CustodyProbeResponse;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.domain.CustodyProbeStatus;
import es.ual.node.custodyliveness.ports.out.CustodyEscalationPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInterestPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInventoryPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInventoryPort.ExpiredCustodyEntry;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import es.ual.node.custodyliveness.ports.out.RemoteCustodyProbeClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates custody liveness probe sessions (legacy outbound origen→custodian flow).
 *
 * <p>La política de recompose del archivo vive en el {@code FileIntegrityRiskOrchestrator}, basada
 * en risk score agregado por archivo a partir del estado persistido en {@code
 * client_fragment_placement.health_status}.
 *
 * <p>Este service mantiene:
 *
 * <ul>
 *   <li>{@link #handleInboundProbe} — handler del endpoint legacy {@code POST
 *       /custody/liveness/probes} (origen→custodian).
 *   <li>{@link #processDueOutboundSessions} — worker scheduled outbound (origen→custodian) +
 *       escalación {@code RETURN_TO_TUTOR} cuando custodian no responde tras retries.
 *   <li>{@link #scheduleProbeNow} — admin trigger (NO USADO).
 * </ul>
 *
 * <p>Coexiste transitoriamente con el flujo nuevo {@code custodian-initiated}: {@link
 * OriginInboundKeepListService} + {@link CustodianOutboundKeepListService} + {@link
 * OriginInverseProbeService}.
 */
public class CustodyLivenessService {

  private static final Logger LOGGER = LoggerFactory.getLogger(CustodyLivenessService.class);

  private final CustodyProbeSessionPort sessionPort;
  private final CustodyFragmentInterestPort custodyFragmentInterestPort;
  private final CustodyFragmentInventoryPort custodyFragmentInventoryPort;
  private final CustodyEscalationPort custodyEscalationPort;
  private final RemoteCustodyProbeClientPort remoteProbeClientPort;
  private final CustodyLivenessProperties properties;
  private final NodeIdentityContext nodeIdentityContext;
  private final Clock clock;
  private final CustodyLivenessObservabilityService observabilityService;
  private final ObservationRegistry observationRegistry;

  /**
   * Optional lifecycle controls — null in minimal test setups; production wiring via {@link
   * es.ual.node.bootstrap.configuration.CustodyLivenessModuleConfiguration}.
   */
  private final es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort
      fragmentLifecyclePort;

  /** Convenience ctor — observability NoOp + lifecycle null (tests minimal). */
  public CustodyLivenessService(
      final CustodyProbeSessionPort sessionPort,
      final CustodyFragmentInterestPort custodyFragmentInterestPort,
      final CustodyFragmentInventoryPort custodyFragmentInventoryPort,
      final CustodyEscalationPort custodyEscalationPort,
      final RemoteCustodyProbeClientPort remoteProbeClientPort,
      final CustodyLivenessProperties properties,
      final NodeIdentityContext nodeIdentityContext,
      final Clock clock) {
    this(
        sessionPort,
        custodyFragmentInterestPort,
        custodyFragmentInventoryPort,
        custodyEscalationPort,
        remoteProbeClientPort,
        properties,
        nodeIdentityContext,
        clock,
        CustodyLivenessObservabilityService.noop(),
        ObservationRegistry.NOOP,
        null);
  }

  /** Full ctor (production). */
  public CustodyLivenessService(
      final CustodyProbeSessionPort sessionPort,
      final CustodyFragmentInterestPort custodyFragmentInterestPort,
      final CustodyFragmentInventoryPort custodyFragmentInventoryPort,
      final CustodyEscalationPort custodyEscalationPort,
      final RemoteCustodyProbeClientPort remoteProbeClientPort,
      final CustodyLivenessProperties properties,
      final NodeIdentityContext nodeIdentityContext,
      final Clock clock,
      final CustodyLivenessObservabilityService observabilityService,
      final ObservationRegistry observationRegistry,
      final es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort
          fragmentLifecyclePort) {
    if (sessionPort == null
        || custodyFragmentInterestPort == null
        || custodyFragmentInventoryPort == null
        || custodyEscalationPort == null
        || remoteProbeClientPort == null
        || properties == null
        || nodeIdentityContext == null
        || clock == null
        || observabilityService == null
        || observationRegistry == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.sessionPort = sessionPort;
    this.custodyFragmentInterestPort = custodyFragmentInterestPort;
    this.custodyFragmentInventoryPort = custodyFragmentInventoryPort;
    this.custodyEscalationPort = custodyEscalationPort;
    this.remoteProbeClientPort = remoteProbeClientPort;
    this.properties = properties;
    this.nodeIdentityContext = nodeIdentityContext;
    this.clock = clock;
    this.observabilityService = observabilityService;
    this.observationRegistry = observationRegistry;
    this.fragmentLifecyclePort = fragmentLifecyclePort;
  }

  /** Handles an inbound probe and stores inbound session trace. */
  public CustodyProbeResponse handleInboundProbe(final CustodyProbeRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (request.requestId() == null || request.requestId().isBlank()) {
      throw new IllegalArgumentException("requestId must not be blank");
    }
    if (request.requesterNodeId() == null || request.requesterNodeId().isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }

    final Instant now = Instant.now(clock);
    final String requesterNodeId = request.requesterNodeId().trim();
    final String sessionId = "inbound-" + request.requestId().trim();
    final CustodyProbeSession previousInbound = sessionPort.findById(sessionId).orElse(null);
    final String inboundRequesterTutor =
        request.requesterTutorBaseUrl() == null || request.requesterTutorBaseUrl().isBlank()
            ? (previousInbound == null ? null : previousInbound.remoteTutorBaseUrl())
            : request.requesterTutorBaseUrl().trim();
    final CustodyProbeSession inboundSession =
        new CustodyProbeSession(
            sessionId,
            requesterNodeId,
            CustodyProbeDirection.INBOUND,
            CustodyProbeStatus.ACTIVE,
            0,
            now,
            now,
            now.plusSeconds(
                computeJitteredDelaySeconds(Math.max(1L, properties.getBaseIntervalSeconds()))),
            null,
            now.plusSeconds(Math.max(1L, properties.getReverseProbeCooldownSeconds())),
            now,
            now,
            inboundRequesterTutor);
    sessionPort.save(inboundSession);
    observabilityService.onInboundProbeHandled();
    observabilityService.onTransition(previousInbound, inboundSession);

    final Map<String, Boolean> requiredByFragmentId = new LinkedHashMap<>();
    if (request.fragments() != null) {
      for (var fragment : request.fragments()) {
        final String fragmentId = fragment.fragmentId() == null ? "" : fragment.fragmentId().trim();
        if (fragmentId.isBlank()) {
          continue;
        }
        final boolean stillRequired =
            custodyFragmentInterestPort.isStillRequired(fragment, requesterNodeId);
        requiredByFragmentId.merge(
            fragmentId, stillRequired, (existing, incoming) -> existing || incoming);
      }
    }

    final List<String> required = new ArrayList<>();
    final List<String> releasable = new ArrayList<>();
    for (Map.Entry<String, Boolean> entry : requiredByFragmentId.entrySet()) {
      if (entry.getValue()) {
        required.add(entry.getKey());
      } else {
        releasable.add(entry.getKey());
      }
    }

    LOGGER
        .atInfo()
        .setMessage("Custody liveness inbound probe processed")
        .addKeyValue("requestId", request.requestId().trim())
        .addKeyValue("requesterNodeId", requesterNodeId)
        .addKeyValue("requiredCount", required.size())
        .addKeyValue("releasableCount", releasable.size())
        .log();

    return new CustodyProbeResponse(
        request.requestId().trim(),
        required,
        releasable,
        false,
        now.plusSeconds(Math.max(1L, properties.getReverseProbeCooldownSeconds())),
        now);
  }

  /** Schedules an immediate outbound probe for a remote node. */
  public CustodyProbeSession scheduleProbeNow(final String remoteNodeId) {
    if (remoteNodeId == null || remoteNodeId.isBlank()) {
      throw new IllegalArgumentException("remoteNodeId must not be blank");
    }
    final String remoteNodeKey = remoteNodeId.trim();
    final Optional<CustodyProbeSession> existing = findDeduplicableOutboundSession(remoteNodeKey);
    if (existing.isPresent()) {
      observabilityService.onOutboundSessionScheduled(true);
      LOGGER
          .atInfo()
          .setMessage("Custody liveness outbound session deduplicated")
          .addKeyValue("remoteNodeId", remoteNodeKey)
          .addKeyValue("sessionId", existing.get().sessionId())
          .addKeyValue("status", existing.get().status())
          .log();
      return existing.get();
    }
    final Instant now = Instant.now(clock);
    final CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            "outbound-" + UUID.randomUUID(),
            remoteNodeKey,
            CustodyProbeDirection.OUTBOUND,
            CustodyProbeStatus.ACTIVE,
            0,
            null,
            null,
            now,
            null,
            null,
            now,
            now);
    sessionPort.save(session);
    observabilityService.onOutboundSessionScheduled(false);
    observabilityService.onTransition(null, session);
    LOGGER
        .atInfo()
        .setMessage("Custody liveness outbound session scheduled")
        .addKeyValue("remoteNodeId", remoteNodeKey)
        .addKeyValue("sessionId", session.sessionId())
        .log();
    return session;
  }

  /** Returns session by id. */
  public CustodyProbeSession findSession(final String sessionId) {
    return sessionPort
        .findById(sessionId)
        .orElseThrow(() -> new NoSuchElementException("session not found"));
  }

  /** Returns sessions for a remote node. */
  public List<CustodyProbeSession> findByRemoteNodeId(final String remoteNodeId) {
    return sessionPort.findByRemoteNodeId(remoteNodeId);
  }

  /** Returns all sessions ordered by latest updates first. */
  public List<CustodyProbeSession> findAllSessions() {
    return sessionPort.findAll();
  }

  /** Returns operational metrics snapshot. */
  public CustodyLivenessMetricsSnapshot metricsSnapshot() {
    return observabilityService.snapshot();
  }

  /** Processes due outbound sessions with a conservative retry policy. */
  public void processDueOutboundSessions() {
    if (!properties.isEnabled()) {
      return;
    }
    final Instant now = Instant.now(clock);
    final List<CustodyProbeSession> due =
        sessionPort.findDueOutbound(now, Math.max(1, properties.getBatchSize()));
    for (CustodyProbeSession session : due) {
      Observation.createNotStarted("node.custody.probe.evaluate", observationRegistry)
          .lowCardinalityKeyValue(
              "peer.node.id", session.remoteNodeId() != null ? session.remoteNodeId() : "null")
          .observe(() -> processSingleOutboundSession(session, now));
    }
  }

  private void processSingleOutboundSession(final CustodyProbeSession session, final Instant now) {
    final String requestId = "probe-" + session.sessionId() + "-" + (session.attemptCount() + 1);
    final List<CustodyProbeFragment> fragments =
        custodyFragmentInventoryPort.findCustodiedForRequester(session.remoteNodeId(), now);
    final CustodyProbeRequest request =
        CustodyProbeRequest.withoutRequesterTutor(
            requestId,
            nodeIdentityContext.nodeId(),
            session.remoteNodeId(),
            fragments,
            now,
            Math.max(1L, properties.getReverseProbeCooldownSeconds()));

    try {
      final CustodyProbeResponse response =
          remoteProbeClientPort.probe(session.remoteNodeId(), request);
      final Instant reverseCooldown =
          response.reverseProbeNotBefore() == null
              ? session.reverseProbeCooldownUntil()
              : response.reverseProbeNotBefore();
      final CustodyProbeSession updated =
          CustodyProbeSession.withoutRemoteTutor(
              session.sessionId(),
              session.remoteNodeId(),
              session.direction(),
              CustodyProbeStatus.ACTIVE,
              0,
              now,
              now,
              now.plusSeconds(computeSuccessDelaySeconds(fragments.size())),
              null,
              reverseCooldown,
              session.createdAt(),
              now);
      sessionPort.save(updated);
      observabilityService.onOutboundProbeSuccess();
      observabilityService.onTransition(session, updated);
      renewStillRequiredFragments(response, now);
      logTransition(session, updated, "probe_success", null);
    } catch (RuntimeException exception) {
      final int nextAttempt = session.attemptCount() + 1;
      final boolean exhausted = nextAttempt >= resolveEscalationAttemptThreshold();
      final CustodyProbeStatus nextStatus =
          exhausted ? CustodyProbeStatus.UNRESPONSIVE : CustodyProbeStatus.SUSPECT;
      final Instant nextRun =
          exhausted ? null : now.plusSeconds(computeRetryDelaySeconds(nextAttempt));
      final String reason = simplifyError(exception);
      observabilityService.onOutboundProbeFailure();
      if (exhausted) {
        applyEscalation(session, fragments, reason, now, nextAttempt);
        return;
      }
      final CustodyProbeSession updated =
          CustodyProbeSession.withoutRemoteTutor(
              session.sessionId(),
              session.remoteNodeId(),
              session.direction(),
              nextStatus,
              nextAttempt,
              session.lastSuccessAt(),
              now,
              nextRun,
              reason,
              session.reverseProbeCooldownUntil(),
              session.createdAt(),
              now);
      sessionPort.save(updated);
      observabilityService.onTransition(session, updated);
      logTransition(session, updated, "probe_failure", reason);
    }
  }

  /**
   * Escalation por expiración TTL sin probe activo.
   *
   * <p>Procesa fragments cuyo TTL ha expirado y NO han sido renovados por el ciclo de probes
   * outbound (típicamente cluster down durante el TTL window u origen permanently unreachable).
   * Para cada grupo {@code (requesterNodeId, fragments)} sintetiza/reusa una probe session
   * UNRESPONSIVE e invoca {@link #applyEscalation} — reusa todo el path existente:
   *
   * <ul>
   *   <li>Si tutor del requester reachable → {@link
   *       es.ual.node.custodyliveness.ports.out.CustodyEscalationPort#handleUnresponsive} lo migra
   *       a {@code recovery_orphan_fragment} via POST /recovery/fragments y borra custody local.
   *   <li>Si tutor unreachable → {@code deferEscalationAndRenewTtl} extiende TTL +N + emite {@code
   *       ESCALATION_DEFERRED_TUTOR_DOWN}. El fragment se MANTIENE en {@code custody_fragment}
   *       hasta el siguiente cycle (zero data loss silenciosa).
   * </ul>
   */
  public void escalateExpiredCustodyFragments() {
    if (!properties.isEnabled()) {
      return;
    }
    final Instant now = Instant.now(clock);
    final int batchSize = Math.max(1, properties.getBatchSize());
    final List<ExpiredCustodyEntry> expired =
        custodyFragmentInventoryPort.findExpiredCustodied(now, batchSize);
    if (expired.isEmpty()) {
      return;
    }

    final Map<String, List<ExpiredCustodyEntry>> byRequester =
        expired.stream()
            .filter(entry -> entry.requesterNodeId() != null && !entry.requesterNodeId().isBlank())
            .collect(
                Collectors.groupingBy(
                    ExpiredCustodyEntry::requesterNodeId, LinkedHashMap::new, Collectors.toList()));

    for (Map.Entry<String, List<ExpiredCustodyEntry>> entry : byRequester.entrySet()) {
      final String requesterNodeId = entry.getKey();
      final List<CustodyProbeFragment> fragments =
          entry.getValue().stream().map(ExpiredCustodyEntry::toProbeFragment).toList();
      final String sessionId = "expiry-escalation-" + requesterNodeId;

      final CustodyProbeSession session =
          sessionPort
              .findById(sessionId)
              .orElseGet(
                  () ->
                      CustodyProbeSession.withoutRemoteTutor(
                          sessionId,
                          requesterNodeId,
                          CustodyProbeDirection.OUTBOUND,
                          CustodyProbeStatus.UNRESPONSIVE,
                          0,
                          null,
                          now,
                          null,
                          "TTL_EXPIRED_NO_PROBE",
                          null,
                          now,
                          now));

      final int attemptCount = session.attemptCount() + 1;
      try {
        applyEscalation(session, fragments, "TTL_EXPIRED_NO_PROBE", now, attemptCount);
        observabilityService.onExpiryEscalation(fragments.size());
      } catch (RuntimeException ex) {
        LOGGER
            .atWarn()
            .setMessage("Expiry escalation failed for requester; will retry next cycle")
            .addKeyValue("requesterNodeId", requesterNodeId)
            .addKeyValue("fragmentCount", fragments.size())
            .addKeyValue("error", simplifyError(ex))
            .log();
      }
    }
  }

  private void applyEscalation(
      final CustodyProbeSession session,
      final List<CustodyProbeFragment> fragments,
      final String reason,
      final Instant now,
      final int attemptCount) {
    final CustodyEscalationPolicy policy = properties.getEscalationPolicy();
    try {
      custodyEscalationPort.handleUnresponsive(session, fragments, reason, now, policy);
      final CustodyProbeSession escalated =
          CustodyProbeSession.withoutRemoteTutor(
              session.sessionId(),
              session.remoteNodeId(),
              session.direction(),
              CustodyProbeStatus.ESCALATED,
              attemptCount,
              session.lastSuccessAt(),
              now,
              null,
              "Escalated with " + policy + ": " + reason,
              session.reverseProbeCooldownUntil(),
              session.createdAt(),
              now);
      sessionPort.save(escalated);
      // Solo emitir transición/log si el status realmente cambia. Bajo políticas idempotentes
      // (e.g. KEEP_AND_ALERT con TTL expirado no renovable) el worker re-procesa la misma
      // sesión cada ciclo; sin esta guarda spammeamos un "transition" cada 60s con
      // statusBefore=ESCALATED statusAfter=ESCALATED, que confunde más que informa.
      if (session.status() != CustodyProbeStatus.ESCALATED) {
        observabilityService.onTransition(session, escalated);
        logTransition(session, escalated, "escalation_applied", reason);
      }
    } catch (RuntimeException escalationFailure) {
      final CustodyProbeSession unresponsive =
          CustodyProbeSession.withoutRemoteTutor(
              session.sessionId(),
              session.remoteNodeId(),
              session.direction(),
              CustodyProbeStatus.UNRESPONSIVE,
              attemptCount,
              session.lastSuccessAt(),
              now,
              null,
              "Escalation failed: " + simplifyError(escalationFailure),
              session.reverseProbeCooldownUntil(),
              session.createdAt(),
              now);
      sessionPort.save(unresponsive);
      observabilityService.onTransition(session, unresponsive);
      logTransition(session, unresponsive, "escalation_failed", simplifyError(escalationFailure));
    }
  }

  private String simplifyError(final RuntimeException exception) {
    if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
      return exception.getMessage();
    }
    return exception.getClass().getSimpleName();
  }

  private int resolveEscalationAttemptThreshold() {
    final int configured = properties.getEscalationAttemptThreshold();
    if (configured > 0) {
      return configured;
    }
    return Math.max(1, properties.getMaxFastRetries());
  }

  private Optional<CustodyProbeSession> findDeduplicableOutboundSession(final String remoteNodeId) {
    return sessionPort.findByRemoteNodeId(remoteNodeId).stream()
        .filter(candidate -> candidate.direction() == CustodyProbeDirection.OUTBOUND)
        .filter(
            candidate ->
                candidate.status() == CustodyProbeStatus.ACTIVE
                    || candidate.status() == CustodyProbeStatus.SUSPECT)
        .filter(candidate -> candidate.nextAttemptAt() != null)
        .findFirst();
  }

  private void logTransition(
      final CustodyProbeSession previous,
      final CustodyProbeSession current,
      final String cause,
      final String detail) {
    LOGGER
        .atInfo()
        .setMessage("Custody liveness session transition")
        .addKeyValue("sessionId", current.sessionId())
        .addKeyValue("remoteNodeId", current.remoteNodeId())
        .addKeyValue("statusBefore", previous == null ? null : previous.status())
        .addKeyValue("statusAfter", current.status())
        .addKeyValue("attemptBefore", previous == null ? null : previous.attemptCount())
        .addKeyValue("attemptAfter", current.attemptCount())
        .addKeyValue("cause", cause)
        .addKeyValue("detail", detail)
        .log();
  }

  private long computeSuccessDelaySeconds(final int fragmentCount) {
    long delay = Math.max(1L, properties.getBaseIntervalSeconds());
    if (properties.isAdaptiveIntervalsEnabled()) {
      if (fragmentCount <= 0) {
        delay = delay * 2L;
      } else if (fragmentCount >= Math.max(1, properties.getHighLoadFragmentThreshold())) {
        delay = Math.max(1L, delay / 2L);
      }
      delay = clampAdaptiveDelay(delay);
    }
    return computeJitteredDelaySeconds(delay);
  }

  /** Renews custody on stillRequired fragments under expiry horizon. */
  private void renewStillRequiredFragments(final CustodyProbeResponse response, final Instant now) {
    if (fragmentLifecyclePort == null
        || response == null
        || response.stillRequiredFragmentIds() == null
        || response.stillRequiredFragmentIds().isEmpty()) {
      return;
    }
    final long horizon = Math.max(1L, properties.getRenewalHorizonSeconds());
    final long extendBy = Math.max(1L, properties.getRenewalSeconds());
    for (String fragmentId : response.stillRequiredFragmentIds()) {
      if (fragmentId == null || fragmentId.isBlank()) {
        continue;
      }
      fragmentLifecyclePort
          .findByFragmentId(fragmentId)
          .ifPresent(
              stored -> {
                final long secondsToExpiry =
                    stored.expiresAt().getEpochSecond() - now.getEpochSecond();
                if (secondsToExpiry < horizon) {
                  fragmentLifecyclePort.extendCustody(fragmentId, extendBy);
                }
              });
    }
  }

  private long computeRetryDelaySeconds(final int nextAttempt) {
    final long fastRetry = Math.max(1L, properties.getFastRetryIntervalSeconds());
    long delay = fastRetry;
    if (properties.isAdaptiveIntervalsEnabled()) {
      final long scaled = fastRetry * Math.max(1, nextAttempt);
      final long retryMaxWindow =
          Math.max(
              fastRetry,
              Math.min(
                  Math.max(1L, properties.getBaseIntervalSeconds()),
                  Math.max(1L, properties.getMaxAdaptiveIntervalSeconds())));
      delay = Math.min(retryMaxWindow, scaled);
      delay = clampAdaptiveDelay(delay);
    }
    return computeJitteredDelaySeconds(delay);
  }

  private long clampAdaptiveDelay(final long candidateDelaySeconds) {
    final long min = Math.max(1L, properties.getMinAdaptiveIntervalSeconds());
    final long max = Math.max(min, properties.getMaxAdaptiveIntervalSeconds());
    return Math.min(max, Math.max(min, candidateDelaySeconds));
  }

  private long computeJitteredDelaySeconds(final long baseDelaySeconds) {
    final long safeBase = Math.max(1L, baseDelaySeconds);
    final double ratio = properties.getJitterRatio();
    if (ratio <= 0.0d) {
      return safeBase;
    }
    final double cappedRatio = Math.min(1.0d, ratio);
    final long jitterWindow = Math.max(1L, Math.round(safeBase * cappedRatio));
    final long delta = ThreadLocalRandom.current().nextLong(-jitterWindow, jitterWindow + 1L);
    return Math.max(1L, safeBase + delta);
  }
}
