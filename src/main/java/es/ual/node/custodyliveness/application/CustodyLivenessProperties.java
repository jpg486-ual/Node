package es.ual.node.custodyliveness.application;

import es.ual.node.custodyliveness.domain.CustodyEscalationPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for custody liveness probes. */
@ConfigurationProperties(prefix = "node.custody-liveness")
public class CustodyLivenessProperties {

  private boolean enabled = false;

  /** Default outbound probe cadence in seconds. */
  private long baseIntervalSeconds = 60L;

  /**
   * Seconds added to a fragment's {@code expiresAt} when the outbound probe finds it in the {@code
   * stillRequiredFragmentIds} list and within the renewal horizon.
   */
  private long renewalSeconds = 300L;

  /**
   * Look-ahead window in seconds: a fragment whose remaining TTL is below this threshold gets its
   * custody extended on the next probe success.
   */
  private long renewalHorizonSeconds = 120L;

  private boolean adaptiveIntervalsEnabled = false;
  private long minAdaptiveIntervalSeconds = 60L;
  private long maxAdaptiveIntervalSeconds = 3600L;
  private int highLoadFragmentThreshold = 10;
  private double jitterRatio = 0.0d;
  private int batchSize = 200;
  private long fastRetryIntervalSeconds = 60L;
  private int maxFastRetries = 10;
  private int escalationAttemptThreshold = 0;
  private long reverseProbeCooldownSeconds = 1800L;
  private CustodyEscalationPolicy escalationPolicy = CustodyEscalationPolicy.KEEP_AND_ALERT;
  private long requestTimeoutMillis = 3000L;
  private Map<String, String> remoteBaseUrls = new LinkedHashMap<>();

  /**
   * Fixed delay (millis) entre cycles del {@code CustodyExpiryEscalationWorker}. Default 60s,
   * alineado con el período del custodian probe cycle.
   */
  private long expiryEscalationFixedDelayMillis = 60_000L;

  /**
   * Tolerancia que el origen suma al {@link #baseIntervalSeconds} antes de considerar al custodian
   * silente y disparar el probe inverso. Default conservador 30s. El check efectivo es: {@code now
   * - last_inbound_probe_at > baseIntervalSeconds + inverseProbeToleranceSeconds}.
   */
  private long inverseProbeToleranceSeconds = 30L;

  /**
   * Número de intentos consecutivos del probe inverso sin respuesta del custodian tras los cuales
   * sus fragments transicionan EN_RIESGO → PERDIDO. Default 5. Cada intento incrementa {@code
   * consecutive_failures} en {@code origin_custodian_health} y cada fragment de ese custodian.
   */
  private int unresponsiveThresholdAttempts = 5;

  /**
   * Intervalo del scheduler que ejecuta el origen-side inverse probe worker. Default 30s para
   * detectar custodians silentes con latencia razonable.
   */
  private long inverseProbeCheckIntervalSeconds = 30L;

  /**
   * TTL renewal cuando el escalation RETURN_TO_TUTOR se difiere por tutor caído.
   *
   * <p>Cuando el POST a {@code /recovery/fragments} falla (timeout / 4xx / 5xx no esperado), el
   * port renueva el {@code custody_fragment.expiresAt} con este valor para que el fragment
   * sobreviva al outage del tutor. El próximo ciclo de probes (cada {@link #baseIntervalSeconds})
   * re-disparará el escalation y reintentará el POST. Cuando el tutor vuelva, el siguiente intento
   * sucederá y el fragment migrará a {@code recovery_orphan_fragment} con normalidad.
   *
   * <p>Default 7200s (2h) — coincide con el TTL custody.
   */
  private long escalationTtlRenewalSeconds = 7200L;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public long getBaseIntervalSeconds() {
    return baseIntervalSeconds;
  }

  public void setBaseIntervalSeconds(final long baseIntervalSeconds) {
    this.baseIntervalSeconds = baseIntervalSeconds;
  }

  public long getRenewalSeconds() {
    return renewalSeconds;
  }

  public void setRenewalSeconds(final long renewalSeconds) {
    this.renewalSeconds = renewalSeconds;
  }

  public long getRenewalHorizonSeconds() {
    return renewalHorizonSeconds;
  }

  public void setRenewalHorizonSeconds(final long renewalHorizonSeconds) {
    this.renewalHorizonSeconds = renewalHorizonSeconds;
  }

  public boolean isAdaptiveIntervalsEnabled() {
    return adaptiveIntervalsEnabled;
  }

  public void setAdaptiveIntervalsEnabled(final boolean adaptiveIntervalsEnabled) {
    this.adaptiveIntervalsEnabled = adaptiveIntervalsEnabled;
  }

  public long getMinAdaptiveIntervalSeconds() {
    return minAdaptiveIntervalSeconds;
  }

  public void setMinAdaptiveIntervalSeconds(final long minAdaptiveIntervalSeconds) {
    this.minAdaptiveIntervalSeconds = minAdaptiveIntervalSeconds;
  }

  public long getMaxAdaptiveIntervalSeconds() {
    return maxAdaptiveIntervalSeconds;
  }

  public void setMaxAdaptiveIntervalSeconds(final long maxAdaptiveIntervalSeconds) {
    this.maxAdaptiveIntervalSeconds = maxAdaptiveIntervalSeconds;
  }

  public int getHighLoadFragmentThreshold() {
    return highLoadFragmentThreshold;
  }

  public void setHighLoadFragmentThreshold(final int highLoadFragmentThreshold) {
    this.highLoadFragmentThreshold = highLoadFragmentThreshold;
  }

  public double getJitterRatio() {
    return jitterRatio;
  }

  public void setJitterRatio(final double jitterRatio) {
    this.jitterRatio = jitterRatio;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(final int batchSize) {
    this.batchSize = batchSize;
  }

  public long getFastRetryIntervalSeconds() {
    return fastRetryIntervalSeconds;
  }

  public void setFastRetryIntervalSeconds(final long fastRetryIntervalSeconds) {
    this.fastRetryIntervalSeconds = fastRetryIntervalSeconds;
  }

  public int getMaxFastRetries() {
    return maxFastRetries;
  }

  public void setMaxFastRetries(final int maxFastRetries) {
    this.maxFastRetries = maxFastRetries;
  }

  public int getEscalationAttemptThreshold() {
    return escalationAttemptThreshold;
  }

  public void setEscalationAttemptThreshold(final int escalationAttemptThreshold) {
    this.escalationAttemptThreshold = escalationAttemptThreshold;
  }

  public long getReverseProbeCooldownSeconds() {
    return reverseProbeCooldownSeconds;
  }

  public void setReverseProbeCooldownSeconds(final long reverseProbeCooldownSeconds) {
    this.reverseProbeCooldownSeconds = reverseProbeCooldownSeconds;
  }

  public CustodyEscalationPolicy getEscalationPolicy() {
    return escalationPolicy;
  }

  public void setEscalationPolicy(final CustodyEscalationPolicy escalationPolicy) {
    this.escalationPolicy =
        escalationPolicy == null ? CustodyEscalationPolicy.KEEP_AND_ALERT : escalationPolicy;
  }

  public long getRequestTimeoutMillis() {
    return requestTimeoutMillis;
  }

  public void setRequestTimeoutMillis(final long requestTimeoutMillis) {
    this.requestTimeoutMillis = requestTimeoutMillis;
  }

  public Map<String, String> getRemoteBaseUrls() {
    return remoteBaseUrls;
  }

  public void setRemoteBaseUrls(final Map<String, String> remoteBaseUrls) {
    this.remoteBaseUrls =
        remoteBaseUrls == null ? new LinkedHashMap<>() : new LinkedHashMap<>(remoteBaseUrls);
  }

  public long getEscalationTtlRenewalSeconds() {
    return escalationTtlRenewalSeconds;
  }

  public void setEscalationTtlRenewalSeconds(final long escalationTtlRenewalSeconds) {
    this.escalationTtlRenewalSeconds = escalationTtlRenewalSeconds;
  }

  public long getInverseProbeToleranceSeconds() {
    return inverseProbeToleranceSeconds;
  }

  public void setInverseProbeToleranceSeconds(final long inverseProbeToleranceSeconds) {
    this.inverseProbeToleranceSeconds = Math.max(0L, inverseProbeToleranceSeconds);
  }

  public int getUnresponsiveThresholdAttempts() {
    return unresponsiveThresholdAttempts;
  }

  public void setUnresponsiveThresholdAttempts(final int unresponsiveThresholdAttempts) {
    this.unresponsiveThresholdAttempts = Math.max(1, unresponsiveThresholdAttempts);
  }

  public long getInverseProbeCheckIntervalSeconds() {
    return inverseProbeCheckIntervalSeconds;
  }

  public void setInverseProbeCheckIntervalSeconds(final long inverseProbeCheckIntervalSeconds) {
    this.inverseProbeCheckIntervalSeconds = Math.max(1L, inverseProbeCheckIntervalSeconds);
  }

  public long getExpiryEscalationFixedDelayMillis() {
    return expiryEscalationFixedDelayMillis;
  }

  public void setExpiryEscalationFixedDelayMillis(final long expiryEscalationFixedDelayMillis) {
    this.expiryEscalationFixedDelayMillis = Math.max(1_000L, expiryEscalationFixedDelayMillis);
  }
}
