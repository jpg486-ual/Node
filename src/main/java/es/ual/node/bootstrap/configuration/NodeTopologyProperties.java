package es.ual.node.bootstrap.configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Topology and trust configuration for tutor and discovery supernodes. */
@ConfigurationProperties(prefix = "node.topology")
public class NodeTopologyProperties {

  private String tutorNodeId;
  private String tutorBaseUrl;
  private List<String> discoverySupernodes = new ArrayList<>();
  private List<String> tutorAcceptedPublicKeys = new ArrayList<>();
  private List<String> acceptedFragmentSenderKeys = new ArrayList<>();

  /**
   * Mapping de {@code requesterNodeId → baseUrl} para los nodos supervisados por este tutor.
   * Necesario para que el {@code TutorManifestKeepListWorker} pueda invocar el endpoint inverso
   * ({@code GET /ops/tutor/manifest-keep-list}) en cada origen. En setups dev docker se popula via
   * env (MANIFEST_SUPERVISED_BASE_URLS_NODE1=http://node1:8080 etc).
   */
  private Map<String, String> supervisedBaseUrls = new HashMap<>();

  /**
   * Returns configured tutor node identifier.
   *
   * @return tutor node id
   */
  public String getTutorNodeId() {
    return tutorNodeId;
  }

  /**
   * Sets configured tutor node identifier.
   *
   * @param tutorNodeId tutor node id
   */
  public void setTutorNodeId(final String tutorNodeId) {
    this.tutorNodeId = tutorNodeId;
  }

  /**
   * Returns configured tutor base URL.
   *
   * @return tutor base URL
   */
  public String getTutorBaseUrl() {
    return tutorBaseUrl;
  }

  /**
   * Sets configured tutor base URL.
   *
   * @param tutorBaseUrl tutor base URL
   */
  public void setTutorBaseUrl(final String tutorBaseUrl) {
    this.tutorBaseUrl = tutorBaseUrl;
  }

  /**
   * Returns discovery supernode endpoints.
   *
   * @return discovery supernode URLs
   */
  public List<String> getDiscoverySupernodes() {
    return discoverySupernodes;
  }

  /**
   * Sets discovery supernode endpoints.
   *
   * @param discoverySupernodes discovery supernode URLs
   */
  public void setDiscoverySupernodes(final List<String> discoverySupernodes) {
    this.discoverySupernodes =
        discoverySupernodes == null ? new ArrayList<>() : new ArrayList<>(discoverySupernodes);
  }

  /**
   * Returns whitelist of public keys accepted by tutor recovery endpoint.
   *
   * @return accepted public keys
   */
  public List<String> getTutorAcceptedPublicKeys() {
    return tutorAcceptedPublicKeys;
  }

  /**
   * Sets whitelist of public keys accepted by tutor recovery endpoint.
   *
   * @param tutorAcceptedPublicKeys accepted public keys
   */
  public void setTutorAcceptedPublicKeys(final List<String> tutorAcceptedPublicKeys) {
    this.tutorAcceptedPublicKeys =
        tutorAcceptedPublicKeys == null
            ? new ArrayList<>()
            : new ArrayList<>(tutorAcceptedPublicKeys);
  }

  /**
   * Returns whitelist of public keys accepted by the general fragment custody endpoint ({@code
   * /custody/fragments}). Distinct from {@link #tutorAcceptedPublicKeys}: a node can accept
   * fragments from network peers without taking on the tutor role.
   *
   * @return accepted fragment sender public keys
   */
  public List<String> getAcceptedFragmentSenderKeys() {
    return acceptedFragmentSenderKeys;
  }

  /**
   * Sets whitelist of public keys accepted by the fragment custody endpoint.
   *
   * @param acceptedFragmentSenderKeys accepted fragment sender public keys
   */
  public void setAcceptedFragmentSenderKeys(final List<String> acceptedFragmentSenderKeys) {
    this.acceptedFragmentSenderKeys =
        acceptedFragmentSenderKeys == null
            ? new ArrayList<>()
            : new ArrayList<>(acceptedFragmentSenderKeys);
  }

  /**
   * Returns mapping of supervised node id → base URL.
   *
   * @return supervised base URLs
   */
  public Map<String, String> getSupervisedBaseUrls() {
    return supervisedBaseUrls;
  }

  /**
   * Sets mapping of supervised node id → base URL.
   *
   * @param supervisedBaseUrls supervised base URLs
   */
  public void setSupervisedBaseUrls(final Map<String, String> supervisedBaseUrls) {
    this.supervisedBaseUrls =
        supervisedBaseUrls == null ? new HashMap<>() : new HashMap<>(supervisedBaseUrls);
  }
}
