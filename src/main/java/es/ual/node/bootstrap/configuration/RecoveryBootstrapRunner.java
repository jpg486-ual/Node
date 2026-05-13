package es.ual.node.bootstrap.configuration;

import es.ual.node.recovery.application.NodeFsRestoreService;
import es.ual.node.recovery.application.RecoveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Triggers node filesystem restore on bootstrap when {@code node.recovery.mode=RESTORE}. Wired only
 * by {@link RecoveryModuleConfiguration} when the mode property says RESTORE; no-op in NORMAL mode.
 *
 * <p>La lógica de dispatch LAZY/ACTIVE se eliminó porque el {@code FileIntegrityRiskOrchestrator}
 * procesa el estado de cada placement periódicamente con política de risk score agregado y dispara
 * recompose total cuando se cruza el umbral. El bootstrap runner solo invoca {@link
 * NodeFsRestoreService#restore()} para reconstruir el catalog (manifest + placements + fs_entry)
 * desde el tutor; el orchestrator scheduled toma el relevo desde su primer tick post-bootstrap.
 */
public class RecoveryBootstrapRunner implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryBootstrapRunner.class);

  private final NodeFsRestoreService nodeFsRestoreService;
  private final RecoveryProperties recoveryProperties;

  /** Creates runner. */
  public RecoveryBootstrapRunner(
      final NodeFsRestoreService nodeFsRestoreService,
      final RecoveryProperties recoveryProperties) {
    if (nodeFsRestoreService == null || recoveryProperties == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.nodeFsRestoreService = nodeFsRestoreService;
    this.recoveryProperties = recoveryProperties;
  }

  @Override
  public void run(final ApplicationArguments args) {
    if (recoveryProperties.getMode() != RecoveryProperties.RestoreMode.RESTORE) {
      return;
    }
    LOGGER.atInfo().setMessage("Bootstrap recovery: triggering restore from tutor manifests").log();
    final NodeFsRestoreService.RestoreSummary summary = nodeFsRestoreService.restore();
    LOGGER
        .atInfo()
        .setMessage("Bootstrap recovery completed")
        .addKeyValue("totalManifests", summary.totalManifests())
        .addKeyValue("created", summary.created())
        .addKeyValue("reused", summary.reused())
        .addKeyValue("skipped", summary.skipped())
        .log();
    // El FileIntegrityRiskOrchestrator toma el relevo periódicamente: evalúa risk
    // score por archivo a partir de los placements reconstruidos y dispara recompose total cuando
    // detecta que el umbral se cruza.
  }
}
