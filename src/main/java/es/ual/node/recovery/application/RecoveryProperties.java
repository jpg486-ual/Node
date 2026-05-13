package es.ual.node.recovery.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Recovery configuration for tutor custody behavior. */
@ConfigurationProperties(prefix = "node.recovery")
public class RecoveryProperties {

  private String payloadDirectory = "./logs/recovery-payload";
  private RestoreMode mode = RestoreMode.NORMAL;
  private RestoreStrategy restoreStrategy = RestoreStrategy.METADATA_ONLY;
  private final Consistency consistency = new Consistency();

  /**
   * Fracción del risk score agregado a partir de la cual el orchestrator dispara recompose total
   * del archivo. {@code riskScore = (count(EN_RIESGO) * 0.5 + count(PERDIDO)) / n}. Default {@code
   * 0.34} (un placement perdido en RS(3,2) ya cruza el umbral). Configurable.
   */
  private double recomposeThresholdFraction = 0.34d;

  /**
   * Número mínimo de placements OK requeridos para intentar recompose. Si {@code count(OK) <
   * min-healthy} el orchestrator emite ADMIN_ALERT {@code FILE_UNRECOVERABLE} en lugar de intentar
   * el recompose. Default 0: el orchestrator usa el {@code k} del manifest como límite efectivo
   * cuando este valor es 0; valor explícito permite override por property para tests/escenarios
   * extremos.
   */
  private int recomposeMinHealthy = 0;

  /**
   * Cadencia del scheduler {@code FileIntegrityRiskWorker} que evalúa el estado de cada archivo y
   * dispara recomposes cuando el umbral se cruza. Default 60s.
   */
  private long integrityCheckIntervalSeconds = 60L;

  /**
   * Cadencia del worker {@code TutorManifestKeepListWorker} (tutor → origen → keep-list → purga).
   * Default 5 min.
   */
  private long manifestKeepIntervalSeconds = 300L;

  /**
   * Cadencia del worker {@code OriginTutorManifestSyncWorker} (origen detecta silencio del tutor +
   * dispara endpoint inverso). Default 10 min.
   */
  private long tutorSyncIntervalSeconds = 600L;

  /**
   * Tolerancia al desfase entre cadencia tutor→origen y la observación del origen, antes de
   * disparar el endpoint inverso. Default 60s.
   */
  private long tutorSyncToleranceSeconds = 60L;

  /**
   * Returns directory used to store recovery payload bytes.
   *
   * @return payload directory path
   */
  public String getPayloadDirectory() {
    return payloadDirectory;
  }

  /**
   * Sets directory used to store recovery payload bytes.
   *
   * @param payloadDirectory payload directory path
   */
  public void setPayloadDirectory(final String payloadDirectory) {
    this.payloadDirectory =
        payloadDirectory == null || payloadDirectory.isBlank()
            ? "./logs/recovery-payload"
            : payloadDirectory.trim();
  }

  /**
   * Returns consistency maintenance settings.
   *
   * @return consistency settings
   */
  public Consistency getConsistency() {
    return consistency;
  }

  /**
   * Returns startup recovery mode. {@code NORMAL} skips restore; {@code RESTORE} triggers {@code
   * NodeFsRestoreService} on bootstrap.
   *
   * @return restore mode
   */
  public RestoreMode getMode() {
    return mode;
  }

  /**
   * Sets startup recovery mode.
   *
   * @param mode mode
   */
  public void setMode(final RestoreMode mode) {
    this.mode = mode == null ? RestoreMode.NORMAL : mode;
  }

  /**
   * Returns restore strategy. {@code METADATA_ONLY} restores fs_entry rows from tutor-custodied
   * manifests without bytes (default; bytes recovered on demand from neighbor nodes). {@code
   * BYTES_FROM_TUTOR} additionally pulls bytes from tutor-recovered fragments via {@code POST
   * /recovery/fragments/reconstruct}.
   *
   * @return restore strategy
   */
  public RestoreStrategy getRestoreStrategy() {
    return restoreStrategy;
  }

  /**
   * Sets restore strategy.
   *
   * @param restoreStrategy strategy
   */
  public void setRestoreStrategy(final RestoreStrategy restoreStrategy) {
    this.restoreStrategy =
        restoreStrategy == null ? RestoreStrategy.METADATA_ONLY : restoreStrategy;
  }

  /** Startup recovery mode. */
  public enum RestoreMode {
    NORMAL,
    RESTORE
  }

  /** Restore strategy when {@link RestoreMode#RESTORE} is active. */
  public enum RestoreStrategy {
    METADATA_ONLY,
    BYTES_FROM_TUTOR
  }

  public double getRecomposeThresholdFraction() {
    return recomposeThresholdFraction;
  }

  public void setRecomposeThresholdFraction(final double recomposeThresholdFraction) {
    if (recomposeThresholdFraction < 0d || recomposeThresholdFraction > 1d) {
      throw new IllegalArgumentException("recompose-threshold-fraction must be in [0,1]");
    }
    this.recomposeThresholdFraction = recomposeThresholdFraction;
  }

  public int getRecomposeMinHealthy() {
    return recomposeMinHealthy;
  }

  public void setRecomposeMinHealthy(final int recomposeMinHealthy) {
    this.recomposeMinHealthy = Math.max(0, recomposeMinHealthy);
  }

  public long getIntegrityCheckIntervalSeconds() {
    return integrityCheckIntervalSeconds;
  }

  public void setIntegrityCheckIntervalSeconds(final long integrityCheckIntervalSeconds) {
    this.integrityCheckIntervalSeconds = Math.max(1L, integrityCheckIntervalSeconds);
  }

  public long getManifestKeepIntervalSeconds() {
    return manifestKeepIntervalSeconds;
  }

  public void setManifestKeepIntervalSeconds(final long manifestKeepIntervalSeconds) {
    this.manifestKeepIntervalSeconds = Math.max(1L, manifestKeepIntervalSeconds);
  }

  public long getTutorSyncIntervalSeconds() {
    return tutorSyncIntervalSeconds;
  }

  public void setTutorSyncIntervalSeconds(final long tutorSyncIntervalSeconds) {
    this.tutorSyncIntervalSeconds = Math.max(1L, tutorSyncIntervalSeconds);
  }

  public long getTutorSyncToleranceSeconds() {
    return tutorSyncToleranceSeconds;
  }

  public void setTutorSyncToleranceSeconds(final long tutorSyncToleranceSeconds) {
    this.tutorSyncToleranceSeconds = Math.max(0L, tutorSyncToleranceSeconds);
  }

  /** Consistency maintenance properties. */
  public static final class Consistency {

    private boolean enabled = true;
    private long workerFixedDelayMillis = 30000L;
    private int cleanupBatchSize = 200;
    private int reconciliationBatchSize = 200;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
    }

    public long getWorkerFixedDelayMillis() {
      return workerFixedDelayMillis;
    }

    public void setWorkerFixedDelayMillis(final long workerFixedDelayMillis) {
      this.workerFixedDelayMillis = Math.max(1000L, workerFixedDelayMillis);
    }

    public int getCleanupBatchSize() {
      return cleanupBatchSize;
    }

    public void setCleanupBatchSize(final int cleanupBatchSize) {
      this.cleanupBatchSize = Math.max(1, cleanupBatchSize);
    }

    public int getReconciliationBatchSize() {
      return reconciliationBatchSize;
    }

    public void setReconciliationBatchSize(final int reconciliationBatchSize) {
      this.reconciliationBatchSize = Math.max(1, reconciliationBatchSize);
    }
  }
}
