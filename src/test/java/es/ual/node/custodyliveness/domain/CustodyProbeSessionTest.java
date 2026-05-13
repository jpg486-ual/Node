package es.ual.node.custodyliveness.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CustodyProbeSession}, focused on remote-tutor field propagation. */
class CustodyProbeSessionTest {

  private static final Instant CREATED = Instant.parse("2026-04-27T10:00:00Z");
  private static final Instant UPDATED = Instant.parse("2026-04-27T10:05:00Z");

  @Test
  void shouldCarryRemoteTutorBaseUrlInCanonicalConstructor() {
    CustodyProbeSession session =
        new CustodyProbeSession(
            "session-1",
            "remote-node",
            CustodyProbeDirection.OUTBOUND,
            CustodyProbeStatus.ACTIVE,
            0,
            null,
            null,
            null,
            null,
            null,
            CREATED,
            UPDATED,
            "https://requester-tutor.example");

    assertEquals("https://requester-tutor.example", session.remoteTutorBaseUrl());
  }

  @Test
  void shouldExposeNullRemoteTutorViaConvenienceFactory() {
    CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            "session-2",
            "remote-node",
            CustodyProbeDirection.OUTBOUND,
            CustodyProbeStatus.ACTIVE,
            0,
            null,
            null,
            null,
            null,
            null,
            CREATED,
            UPDATED);

    assertNull(session.remoteTutorBaseUrl());
  }
}
