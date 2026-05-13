package es.ual.node.negotiation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link NegotiationCreateRequest}, focused on tutor-propagation fields. */
class NegotiationCreateRequestTest {

  private static final String REQUESTER = "requester-node";
  private static final String TARGET = "target-node";
  private static final String SIGNATURE = "requester-signature";

  @Test
  void shouldCreateRequestWithTutorInfoWhenProvided() {
    NegotiationCreateRequest request =
        new NegotiationCreateRequest(
            REQUESTER,
            TARGET,
            1024L,
            4096L,
            TransferMode.FRAGMENTS_ONLY,
            4,
            "RS(6,4)",
            60,
            null,
            SIGNATURE,
            "tutor-node-x",
            "https://tutor-x.example:8080");

    assertEquals("tutor-node-x", request.requesterTutorNodeId());
    assertEquals("https://tutor-x.example:8080", request.requesterTutorBaseUrl());
  }

  @Test
  void shouldCreateRequestWithoutTutorInfoUsingConvenienceConstructor() {
    NegotiationCreateRequest request =
        new NegotiationCreateRequest(
            REQUESTER,
            TARGET,
            1024L,
            4096L,
            TransferMode.FRAGMENTS_ONLY,
            4,
            "RS(6,4)",
            60,
            null,
            SIGNATURE);

    assertNull(request.requesterTutorNodeId());
    assertNull(request.requesterTutorBaseUrl());
  }

  @Test
  void shouldRejectBlankRequesterTutorNodeId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NegotiationCreateRequest(
                REQUESTER,
                TARGET,
                1024L,
                4096L,
                TransferMode.FRAGMENTS_ONLY,
                4,
                "RS(6,4)",
                60,
                null,
                SIGNATURE,
                "  ",
                "https://tutor.example"));
  }

  @Test
  void shouldRejectBlankRequesterTutorBaseUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NegotiationCreateRequest(
                REQUESTER,
                TARGET,
                1024L,
                4096L,
                TransferMode.FRAGMENTS_ONLY,
                4,
                "RS(6,4)",
                60,
                null,
                SIGNATURE,
                "tutor-x",
                "  "));
  }

  @Test
  void shouldRejectInvalidRequesterTutorBaseUrlScheme() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NegotiationCreateRequest(
                REQUESTER,
                TARGET,
                1024L,
                4096L,
                TransferMode.FRAGMENTS_ONLY,
                4,
                "RS(6,4)",
                60,
                null,
                SIGNATURE,
                "tutor-x",
                "ftp://tutor.example"));
  }

  @Test
  void shouldAcceptHttpAndHttpsTutorBaseUrls() {
    NegotiationCreateRequest http =
        new NegotiationCreateRequest(
            REQUESTER,
            TARGET,
            1024L,
            4096L,
            TransferMode.FRAGMENTS_ONLY,
            4,
            "RS(6,4)",
            60,
            null,
            SIGNATURE,
            "tutor-x",
            "http://tutor.example:8080/recovery");

    NegotiationCreateRequest https =
        new NegotiationCreateRequest(
            REQUESTER,
            TARGET,
            1024L,
            4096L,
            TransferMode.FRAGMENTS_ONLY,
            4,
            "RS(6,4)",
            60,
            null,
            SIGNATURE,
            "tutor-x",
            "https://tutor.example");

    assertEquals("http://tutor.example:8080/recovery", http.requesterTutorBaseUrl());
    assertEquals("https://tutor.example", https.requesterTutorBaseUrl());
  }

  @Test
  void shouldTrimTutorFieldsWhenProvided() {
    NegotiationCreateRequest request =
        new NegotiationCreateRequest(
            REQUESTER,
            TARGET,
            1024L,
            4096L,
            TransferMode.FRAGMENTS_ONLY,
            4,
            "RS(6,4)",
            60,
            null,
            SIGNATURE,
            "  tutor-x  ",
            "  https://tutor.example  ");

    assertEquals("tutor-x", request.requesterTutorNodeId());
    assertEquals("https://tutor.example", request.requesterTutorBaseUrl());
  }
}
