package es.ual.node.recovery.application;

import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.recovery.ports.out.FileRecomposePort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Origen-side orchestrator del sistema de integridad de archivos.
 *
 * <p>Consume el estado de salud de cada placement y, por archivo, calcula el risk score agregado:
 *
 * <pre>{@code
 * riskScore = (count(EN_RIESGO) * 0.5 + count(PERDIDO)) / n
 * }</pre>
 *
 * <p>Si {@code riskScore >= recompose-threshold-fraction}:
 *
 * <ul>
 *   <li>{@code count(OK) >= min-healthy} (default {@code k}): dispara <strong>recompose total del
 *       archivo</strong> — descarga k placements OK, RS-decode, re-upload via {@link
 *       FileContentDistributionService#distributeUploadStreaming}. Emite log INFO {@code
 *       FILE_RECOMPOSED} y suma al counter Prometheus.
 *   <li>{@code count(OK) < min-healthy}: emite log WARN {@code FILE_UNRECOVERABLE} severity
 *       critical (alertable desde Loki/Grafana) y suma al counter Prometheus. NO se intenta
 *       recompose.
 * </ul>
 *
 * <p>Si {@code riskScore < threshold}: no-op.
 *
 * <p>La trazabilidad de cada evento queda en (a) counters Prometheus en {@link
 * RecoveryObservabilityService#onFileIntegrityCycle} y (b) logs estructurados con event-code
 * estable + severity + fileId + score. No se persiste audit log adicional.
 *
 * <p><strong>Por qué re-upload total y no redistribución per-fragment</strong>: la única operación
 * legítima de movimiento de bytes en este eje es re-upload total — descargar k vivos → reconstruir
 * → upload normal sobreescribiendo. Eso elimina la complejidad de tracking `clientPlacementsJson`
 * mutable en el tutor (cada re-upload regenera el manifest entero) y preserva la coherencia
 * origen↔tutor automáticamente.
 */
public class FileIntegrityRiskOrchestrator {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileIntegrityRiskOrchestrator.class);

  private final FragmentPlacementPort placementPort;
  private final FileManifestPort manifestPort;
  private final FsEntryPort fsEntryPort;
  private final FileRecomposePort recomposePort;
  private final RecoveryProperties recoveryProperties;
  private final Clock clock;
  private final RecoveryObservabilityService observabilityService;

  /** Creates orchestrator. */
  public FileIntegrityRiskOrchestrator(
      final FragmentPlacementPort placementPort,
      final FileManifestPort manifestPort,
      final FsEntryPort fsEntryPort,
      final FileRecomposePort recomposePort,
      final RecoveryProperties recoveryProperties,
      final Clock clock,
      final RecoveryObservabilityService observabilityService) {
    if (placementPort == null
        || manifestPort == null
        || fsEntryPort == null
        || recomposePort == null
        || recoveryProperties == null
        || clock == null
        || observabilityService == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.placementPort = placementPort;
    this.manifestPort = manifestPort;
    this.fsEntryPort = fsEntryPort;
    this.recomposePort = recomposePort;
    this.recoveryProperties = recoveryProperties;
    this.clock = clock;
    this.observabilityService = observabilityService;
  }

  /**
   * Ejecuta un ciclo completo: itera por archivo, evalúa risk score, dispara recompose o emite
   * señal de fichero irrecuperable según corresponda.
   *
   * @return resumen del ciclo
   */
  public CycleSummary runOnce() {
    final Instant now = Instant.now(clock);
    final List<FragmentPlacement> all = placementPort.findAll();
    final Map<String, List<FragmentPlacement>> byFile =
        all.stream().collect(Collectors.groupingBy(FragmentPlacement::fileId));

    int evaluated = 0;
    int recomposed = 0;
    int recomposeFailures = 0;
    int unrecoverable = 0;
    double maxScoreObserved = 0.0d;

    for (Map.Entry<String, List<FragmentPlacement>> e : byFile.entrySet()) {
      final String fileId = e.getKey();
      final List<FragmentPlacement> placements = e.getValue();
      evaluated++;

      final HealthStats stats = HealthStats.from(placements);
      final FileManifest manifest = manifestPort.findByFileId(fileId).orElse(null);
      if (manifest == null) {
        // No manifest local → no podemos recomponer; saltar silenciosamente.
        continue;
      }
      final int n = manifest.fragmentCount();
      final int k = manifest.redundancyK();
      final double score = (stats.atRisk * 0.5d + stats.perdido) / (double) Math.max(n, 1);
      if (score > maxScoreObserved) {
        maxScoreObserved = score;
      }

      if (score < recoveryProperties.getRecomposeThresholdFraction()) {
        continue;
      }

      final int minHealthy =
          recoveryProperties.getRecomposeMinHealthy() > 0
              ? recoveryProperties.getRecomposeMinHealthy()
              : k;

      if (stats.ok < minHealthy) {
        unrecoverable++;
        LOGGER
            .atWarn()
            .setMessage("File integrity: insufficient healthy placements, file unrecoverable")
            .addKeyValue("event", "FILE_UNRECOVERABLE")
            .addKeyValue("severity", "critical")
            .addKeyValue("fileId", fileId)
            .addKeyValue("ok", stats.ok)
            .addKeyValue("atRisk", stats.atRisk)
            .addKeyValue("perdido", stats.perdido)
            .addKeyValue("n", n)
            .addKeyValue("k", k)
            .addKeyValue("score", score)
            .addKeyValue("detectedAt", now)
            .log();
        continue;
      }

      try {
        recompose(fileId);
        recomposed++;
        LOGGER
            .atInfo()
            .setMessage("File integrity: file recomposed (re-upload total)")
            .addKeyValue("event", "FILE_RECOMPOSED")
            .addKeyValue("fileId", fileId)
            .addKeyValue("score", score)
            .addKeyValue("ok", stats.ok)
            .addKeyValue("atRisk", stats.atRisk)
            .addKeyValue("perdido", stats.perdido)
            .addKeyValue("n", n)
            .addKeyValue("k", k)
            .log();
      } catch (RuntimeException ex) {
        recomposeFailures++;
        LOGGER
            .atWarn()
            .setMessage("File integrity: recompose failed")
            .addKeyValue("event", "FILE_RECOMPOSE_FAILED")
            .addKeyValue("severity", "high")
            .addKeyValue("fileId", fileId)
            .addKeyValue("score", score)
            .addKeyValue("ok", stats.ok)
            .addKeyValue("atRisk", stats.atRisk)
            .addKeyValue("perdido", stats.perdido)
            .addKeyValue("n", n)
            .addKeyValue("k", k)
            .addKeyValue("error", ex.getMessage())
            .log();
      }
    }

    if (evaluated > 0) {
      LOGGER
          .atDebug()
          .setMessage("File integrity cycle complete")
          .addKeyValue("event", "FILE_INTEGRITY_CYCLE")
          .addKeyValue("evaluated", evaluated)
          .addKeyValue("recomposed", recomposed)
          .addKeyValue("recomposeFailures", recomposeFailures)
          .addKeyValue("unrecoverable", unrecoverable)
          .addKeyValue("maxScoreObserved", maxScoreObserved)
          .log();
    }
    observabilityService.onFileIntegrityCycle(
        maxScoreObserved, evaluated, recomposed, recomposeFailures, unrecoverable);
    return new CycleSummary(evaluated, recomposed, recomposeFailures, unrecoverable);
  }

  private void recompose(final String fileId) {
    final FsEntry entry =
        fsEntryPort
            .findByFileId(fileId)
            .orElseThrow(
                () -> new IllegalStateException("FsEntry not found for fileId: " + fileId));
    // Descarga + reconstruct desde k placements OK. Mecanismo existente.
    final byte[] bytes = recomposePort.reconstructFileBytes(fileId);
    if (bytes == null || bytes.length == 0) {
      throw new IllegalStateException("reconstructFileBytes returned empty bytes for " + fileId);
    }
    // Regenera fileId, manifest, placements y propaga al tutor.
    recomposePort.reUploadTotal(entry, bytes);
  }

  /** Resumen de un ciclo del orchestrator. */
  public record CycleSummary(
      int evaluated, int recomposed, int recomposeFailures, int unrecoverable) {}

  /** Contadores agregados de health por archivo. */
  private record HealthStats(int ok, int atRisk, int perdido) {
    static HealthStats from(final List<FragmentPlacement> placements) {
      int ok = 0;
      int atRisk = 0;
      int perdido = 0;
      for (FragmentPlacement p : placements) {
        switch (p.healthStatus()) {
          case OK -> ok++;
          case EN_RIESGO -> atRisk++;
          case PERDIDO -> perdido++;
        }
      }
      return new HealthStats(ok, atRisk, perdido);
    }
  }
}
