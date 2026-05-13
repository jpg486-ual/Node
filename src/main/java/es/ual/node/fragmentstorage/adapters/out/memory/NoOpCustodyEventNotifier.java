package es.ual.node.fragmentstorage.adapters.out.memory;

import es.ual.node.fragmentstorage.ports.out.CustodyEventNotifierPort;

/**
 * Default {@link CustodyEventNotifierPort} adapter active when no other module subscribes typically
 * when {@code node.custody-liveness.enabled=false}). Intentional no-op: the custody fragment is
 * stored but no downstream effect is triggered.
 */
public class NoOpCustodyEventNotifier implements CustodyEventNotifierPort {

  @Override
  public void onCustodyFragmentStored(final String requesterNodeId) {
    // intentional no-op
  }
}
