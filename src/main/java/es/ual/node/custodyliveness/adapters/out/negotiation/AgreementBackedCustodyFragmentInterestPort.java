package es.ual.node.custodyliveness.adapters.out.negotiation;

import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInterestPort;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.negotiation.domain.NegotiationAgreement;
import es.ual.node.negotiation.domain.NegotiationStatus;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluator for "is this fragment still required by the origin?" queries that custodians issue
 * during liveness probes.
 *
 * <p>Order: <b>placement-backed primero, agreement-backed como legacy fallback</b>.
 *
 * <ol>
 *   <li><b>Placement-backed (path productivo dominante)</b> el upload del cliente entra vía {@code
 *       FileContentDistributionService.distributeUploadStreaming} con synthetic {@code
 *       agreementId="client-upload-<UUID>"} que nunca aterriza en {@code negotiation_agreement}. El
 *       row de {@code client_fragment_placement} ES la fuente de verdad y se matchea por {@code
 *       fragmentId}. Es el path 100% del flujo cliente real en cluster cerrado/empresarial.
 *   <li><b>Agreement-backed (LEGACY — solo activo si {@code negotiation-formal-enabled=true})</b>
 *       fragment colocado vía un confirmed {@code NegotiationAgreement}. Si el agreement existe y
 *       está CONFIRMED/no terminal/no expirado, autoriza al custodio a mantener el fragment. En el
 *       modelo cerrado actual ningún flujo productivo persiste agreements; la rama queda como red
 *       de seguridad si se reactiva el endpoint formal {@code /negotiate/**}.
 * </ol>
 *
 * <p>El orden previo (agreement primero) era artefacto del modelo simétrico abierto. Tras la
 * inversión, el lookup productivo se sirve en el path corto y la rama legacy solo se ejercita
 * cuando alguien activa explícitamente la negociación formal.
 */
public class AgreementBackedCustodyFragmentInterestPort implements CustodyFragmentInterestPort {

  private final AgreementRepository agreementRepository;
  private final NodeIdentityContext nodeIdentityContext;
  private final FsEntryPort fsEntryPort;
  private final FragmentPlacementPort fragmentPlacementPort;
  private final CustodyLivenessProperties livenessProperties;
  private final Clock clock;

  /** Creates evaluator. */
  public AgreementBackedCustodyFragmentInterestPort(
      final AgreementRepository agreementRepository,
      final NodeIdentityContext nodeIdentityContext,
      final FsEntryPort fsEntryPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final CustodyLivenessProperties livenessProperties,
      final Clock clock) {
    if (agreementRepository == null
        || nodeIdentityContext == null
        || fsEntryPort == null
        || fragmentPlacementPort == null
        || livenessProperties == null
        || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.agreementRepository = agreementRepository;
    this.nodeIdentityContext = nodeIdentityContext;
    this.fsEntryPort = fsEntryPort;
    this.fragmentPlacementPort = fragmentPlacementPort;
    this.livenessProperties = livenessProperties;
    this.clock = clock;
  }

  @Override
  public boolean isStillRequired(
      final CustodyProbeFragment fragment, final String requesterNodeId) {
    if (fragment == null) {
      return false;
    }
    if (fragment.agreementId() == null || fragment.agreementId().isBlank()) {
      return true;
    }

    // Path productivo (placement-backed): fuente de verdad en el modelo cerrado actual.
    // Cualquier upload cliente con synthetic agreementId="client-upload-<UUID>" se resuelve
    // aquí — la rama legacy de abajo solo se consulta si placement no encuentra el fragment.
    final Optional<FragmentPlacement> placementOpt =
        fragmentPlacementPort.findByFragmentId(fragment.fragmentId());
    if (placementOpt.isPresent()) {
      return isStillRequiredByPlacement(placementOpt.get(), requesterNodeId);
    }

    // Legacy fallback (agreement-backed): solo relevante cuando negotiation-formal-enabled=true
    // y existe un NegotiationAgreement persistido. En el modelo cerrado productivo este branch
    // nunca se ejercita; sobrevive como red de seguridad si se reactiva el path formal /negotiate.
    return isStillRequiredByAgreement(fragment, requesterNodeId);
  }

  /**
   * Legacy path: evalúa la idoneidad del fragment leyendo el {@code NegotiationAgreement}
   * persistido. Solo aplica cuando alguien activó {@code POST /negotiate} formalmente y el
   * agreement vive en {@code negotiation_agreement}.
   */
  private boolean isStillRequiredByAgreement(
      final CustodyProbeFragment fragment, final String requesterNodeId) {
    final NegotiationAgreement agreement =
        agreementRepository.findById(fragment.agreementId().trim()).orElse(null);
    if (agreement == null) {
      return false;
    }

    final Instant now = clock.instant();
    if (agreement.isTerminal() || agreement.isExpiredAt(now)) {
      return false;
    }
    if (agreement.status() != NegotiationStatus.CONFIRMED) {
      return false;
    }

    final String localNodeId = nodeIdentityContext.nodeId();
    if (!localNodeId.equals(agreement.requesterNodeId())) {
      return false;
    }

    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return false;
    }
    if (!requesterNodeId.trim().equals(agreement.targetNodeId())) {
      return false;
    }

    // Si el archivo backing del agreement ha sido borrado por el usuario, o el manifest fileId
    // queda huérfano tras un overwrite, el fragment ya no se requiere y el custodio debe
    // liberarlo en el siguiente probe.
    final String fileId = agreement.fileId();
    if (fileId != null && !fileId.isBlank()) {
      final Optional<FsEntry> fsEntry = fsEntryPort.findByFileId(fileId.trim());
      if (fsEntry.isEmpty() || fsEntry.get().deleted()) {
        return false;
      }
    }

    return true;
  }

  /**
   * Placement-backed evaluation: path productivo dominante en el modelo cerrado actual. El upload
   * cliente con synthetic {@code agreementId="client-upload-<UUID>"} no aterriza en {@code
   * negotiation_agreement}; el row de {@code client_fragment_placement} es la fuente de verdad y se
   * matchea por {@code fragmentId}.
   *
   * <p>El fragment sigue siendo requerido si (a) el placement apunta al custodio que prueba (via
   * {@code custodianNodeId} O {@code custodianBaseUrl}); (b) el {@link FsEntry} backing (resuelto
   * por el {@code fileId} del placement) existe y no es tombstone.
   */
  private boolean isStillRequiredByPlacement(
      final FragmentPlacement placement, final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return false;
    }
    final String trimmedRequester = requesterNodeId.trim();
    if (!custodianMatchesProbeRequester(placement, trimmedRequester)) {
      return false;
    }
    final String fileId = placement.fileId();
    if (fileId == null || fileId.isBlank()) {
      return false;
    }
    final Optional<FsEntry> fsEntry = fsEntryPort.findByFileId(fileId.trim());
    return fsEntry.isPresent() && !fsEntry.get().deleted();
  }

  /**
   * Matches a placement's custodian fields against the cryptographic {@code requesterNodeId}
   * carried in the probe.
   *
   * <p>The placement was written by {@code FileContentDistributionService} with a legacy {@code
   * custodianNodeId="peer@<baseUrl>"} synthetic id, while the probe arrives signed by the
   * cryptographic node id ({@code node-<sha256-prefix>}). The cluster wires the bridge between the
   * two via {@code node.custody-liveness.remote-base-urls.<cryptoId>=<baseUrl>}. We accept any of:
   * (a) the cryptographic id matching the placement's custodianNodeId (post-migration future), (b)
   * the cryptographic id resolving to a baseUrl that matches the placement's {@code
   * custodianBaseUrl} (today's docker setup), (c) the placement's id being the {@code peer@<url>}
   * sentinel that wraps the same baseUrl (self-custody).
   */
  private boolean custodianMatchesProbeRequester(
      final FragmentPlacement placement, final String trimmedRequester) {
    if (trimmedRequester.equals(placement.custodianNodeId())) {
      return true;
    }
    final String placementBaseUrl = placement.custodianBaseUrl();
    if (placementBaseUrl == null || placementBaseUrl.isBlank()) {
      return false;
    }
    final Map<String, String> remoteBaseUrls = livenessProperties.getRemoteBaseUrls();
    if (remoteBaseUrls != null) {
      final String mappedBaseUrl = remoteBaseUrls.get(trimmedRequester);
      if (placementBaseUrl.equals(mappedBaseUrl)) {
        return true;
      }
    }
    // self-custody: the local node legitimately custodies its own fragment under the
    // sentinel "peer@<localBaseUrl>" — when the probe's requesterNodeId matches the local
    // node identity, accept any placement whose baseUrl is the local one.
    if (trimmedRequester.equals(nodeIdentityContext.nodeId())) {
      final String localBaseUrl =
          remoteBaseUrls == null ? null : remoteBaseUrls.get(nodeIdentityContext.nodeId());
      if (localBaseUrl != null && placementBaseUrl.equals(localBaseUrl)) {
        return true;
      }
    }
    return false;
  }
}
