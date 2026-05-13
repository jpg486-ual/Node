package es.ual.node.fragmentstorage.application;

/**
 * Thrown when a sender's public key is not in the custodian's {@code
 * node.topology.acceptedFragmentSenderKeys} whitelist.
 */
public class FragmentCustodyAuthorizationException extends RuntimeException {

  public FragmentCustodyAuthorizationException(final String message) {
    super(message);
  }
}
