package es.ual.node.discovery.application;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryResponse;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.identitysecurity.ports.out.PublicKeyRegistry;
import es.ual.node.shared.domain.FailureDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application service implementing discovery bucket filtering and ratio expansion. */
public class DiscoveryService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryService.class);

  private final PublicKeyRegistry publicKeyRegistry;
  private final DiscoveryCandidateDirectoryPort candidateDirectoryPort;
  private final DiscoveryProperties properties;

  /**
   * Creates discovery service.
   *
   * @param publicKeyRegistry public key registry for requester validation
   * @param candidateDirectoryPort candidate directory port
   * @param properties discovery properties
   */
  public DiscoveryService(
      final PublicKeyRegistry publicKeyRegistry,
      final DiscoveryCandidateDirectoryPort candidateDirectoryPort,
      final DiscoveryProperties properties) {
    if (publicKeyRegistry == null || candidateDirectoryPort == null || properties == null) {
      throw new IllegalArgumentException("Dependencies must not be null");
    }
    this.publicKeyRegistry = publicKeyRegistry;
    this.candidateDirectoryPort = candidateDirectoryPort;
    this.properties = properties;
  }

  /**
   * Discovers eligible candidates based on exact bucket and ratio expansion rules.
   *
   * @param request discovery request
   * @return discovery response
   */
  public DiscoveryResponse discover(final DiscoveryRequest request) {
    if (request == null) {
      throw new DiscoveryException("Discovery request must not be null");
    }
    if (!publicKeyRegistry.isRegistered(request.nodeId())) {
      throw new DiscoveryException("Requesting node is not registered");
    }

    validateDiscoveryPolicies(request);

    final FailureDomain requesterDomain = FailureDomain.of(request.failureDomain());
    final FailureDomain targetDomain =
        request.targetFailureDomain() == null
            ? null
            : FailureDomain.of(request.targetFailureDomain());
    final Map<String, Integer> effectiveDistributionPlan = resolveDistributionPlan(request);
    final int effectiveMaxCandidates =
        Math.min(request.maxCandidates(), properties.getMaxCandidatesLimit());
    final double effectiveRatio = Math.min(request.ratio(), properties.getMaxRatio());
    final long expandedBucket = (long) Math.floor(request.requestedBucket() * effectiveRatio);

    // El origen es candidato valido para custodiar sus propios fragmentos
    // en el modelo cerrado asimetrico. El filtro exclude-self era herencia del modelo
    // de intercambio mutuo donde podia crear loops. Aqui no aplica.
    List<DiscoveryCandidateProfile> activeCandidates =
        candidateDirectoryPort.findActiveCandidates().stream()
            .filter(candidate -> passesFailureDomainFilter(candidate, requesterDomain))
            .filter(candidate -> passesTargetFailureDomainFilter(candidate, targetDomain))
            .collect(Collectors.toList());

    final List<DiscoveryCandidateProfile> exactMatches =
        activeCandidates.stream()
            .filter(candidate -> candidate.isExactMatch(request.requestedBucket()))
            .sorted(
                Comparator.comparingLong(DiscoveryCandidateProfile::originalRequestedBucket)
                    .thenComparing(DiscoveryCandidateProfile::nodeId))
            .toList();

    final Set<String> alreadySelected =
        exactMatches.stream().map(DiscoveryCandidateProfile::nodeId).collect(Collectors.toSet());

    final List<DiscoveryCandidateProfile> expandedMatches =
        activeCandidates.stream()
            .filter(candidate -> !alreadySelected.contains(candidate.nodeId()))
            .filter(
                candidate ->
                    candidate.isRatioExpandedMatch(request.requestedBucket(), expandedBucket))
            .sorted(
                Comparator.comparingLong(DiscoveryCandidateProfile::originalRequestedBucket)
                    .thenComparing(DiscoveryCandidateProfile::nodeId))
            .toList();

    final List<DiscoveryCandidateProfile> merged = new ArrayList<>();
    merged.addAll(exactMatches);
    merged.addAll(expandedMatches);

    final List<DiscoveryCandidateProfile> selectedCandidates =
        selectCandidates(merged, effectiveDistributionPlan, effectiveMaxCandidates);

    final List<DiscoveryResponse.CandidateNode> responseCandidates =
        selectedCandidates.stream()
            .map(
                candidate ->
                    new DiscoveryResponse.CandidateNode(
                        candidate.nodeId(),
                        candidate.baseUrl(),
                        candidate.originalRequestedBucket()))
            .toList();

    LOGGER
        .atInfo()
        .setMessage("Discovery completed")
        .addKeyValue("requesterNodeId", request.nodeId())
        .addKeyValue("requestedBucket", request.requestedBucket())
        .addKeyValue("effectiveRatio", effectiveRatio)
        .addKeyValue("expandedBucket", expandedBucket)
        .addKeyValue("targetFailureDomain", request.targetFailureDomain())
        .addKeyValue("distributionPlanEntries", effectiveDistributionPlan.size())
        .addKeyValue("returnedCandidates", responseCandidates.size())
        .log();

    return new DiscoveryResponse(responseCandidates);
  }

  private void validateDiscoveryPolicies(final DiscoveryRequest request) {
    if (properties.isRequireTargetFailureDomain()
        && (request.targetFailureDomain() == null || request.targetFailureDomain().isBlank())) {
      throw new DiscoveryException("targetFailureDomain is required by discovery policy");
    }

    if (properties.isRequireDistributionPlan()
        && request.distributionPlan().isEmpty()
        && (properties.getDefaultDistributionPlan() == null
            || properties.getDefaultDistributionPlan().isBlank())) {
      throw new DiscoveryException("distributionPlan is required by discovery policy");
    }
  }

  private Map<String, Integer> resolveDistributionPlan(final DiscoveryRequest request) {
    if (!request.distributionPlan().isEmpty()) {
      return request.distributionPlan();
    }
    return parseDistributionPlan(properties.getDefaultDistributionPlan());
  }

  private Map<String, Integer> parseDistributionPlan(final String distributionPlan) {
    if (distributionPlan == null || distributionPlan.isBlank()) {
      return Map.of();
    }

    final Map<String, Integer> parsed = new LinkedHashMap<>();
    final String[] entries = distributionPlan.split(",");
    for (String entry : entries) {
      final String trimmed = entry.trim();
      if (trimmed.isBlank()) {
        continue;
      }

      final String[] pair = trimmed.split(":");
      if (pair.length != 2) {
        throw new DiscoveryException("Invalid distribution plan entry: " + trimmed);
      }

      final String domain = pair[0].trim();
      final int count;
      try {
        count = Integer.parseInt(pair[1].trim());
      } catch (NumberFormatException exception) {
        throw new DiscoveryException("Invalid distribution plan count for entry: " + trimmed);
      }

      if (count <= 0) {
        throw new DiscoveryException("Distribution plan counts must be greater than zero");
      }
      FailureDomain.of(domain);
      parsed.put(domain, count);
    }

    return Map.copyOf(parsed);
  }

  private List<DiscoveryCandidateProfile> selectCandidates(
      final List<DiscoveryCandidateProfile> merged,
      final Map<String, Integer> distributionPlan,
      final int effectiveMaxCandidates) {
    if (distributionPlan.isEmpty()) {
      return merged.stream().limit(effectiveMaxCandidates).toList();
    }

    final List<DiscoveryCandidateProfile> selected = new ArrayList<>();
    final Set<String> selectedNodeIds = new HashSet<>();

    for (Map.Entry<String, Integer> planEntry : distributionPlan.entrySet()) {
      int selectedForDomain = 0;
      final FailureDomain requiredDomain = FailureDomain.of(planEntry.getKey());

      for (DiscoveryCandidateProfile candidate : merged) {
        if (selected.size() >= effectiveMaxCandidates
            || selectedForDomain >= planEntry.getValue()) {
          break;
        }
        if (selectedNodeIds.contains(candidate.nodeId())) {
          continue;
        }

        final FailureDomain candidateDomain = FailureDomain.of(candidate.failureDomain());
        if (!candidateDomain.matches(requiredDomain)) {
          continue;
        }

        selected.add(candidate);
        selectedNodeIds.add(candidate.nodeId());
        selectedForDomain++;
      }
    }

    return selected;
  }

  private boolean passesTargetFailureDomainFilter(
      final DiscoveryCandidateProfile candidate, final FailureDomain targetDomain) {
    if (targetDomain == null) {
      return true;
    }

    final FailureDomain candidateDomain = FailureDomain.of(candidate.failureDomain());
    return candidateDomain.matches(targetDomain);
  }

  private boolean passesFailureDomainFilter(
      final DiscoveryCandidateProfile candidate, final FailureDomain requesterDomain) {
    if (!properties.isFailureDomainFilterEnabled()) {
      return true;
    }

    final FailureDomain candidateDomain = FailureDomain.of(candidate.failureDomain());
    final boolean sameHierarchy =
        candidateDomain.matches(requesterDomain) || requesterDomain.matches(candidateDomain);
    if (!sameHierarchy) {
      return false;
    }

    if (properties.getAllowedFailureDomains().isEmpty()) {
      return true;
    }

    return properties.getAllowedFailureDomains().stream()
        .map(FailureDomain::of)
        .anyMatch(allowed -> candidateDomain.matches(allowed));
  }
}
