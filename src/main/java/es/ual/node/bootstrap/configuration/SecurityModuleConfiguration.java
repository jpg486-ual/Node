package es.ual.node.bootstrap.configuration;

import es.ual.node.bootstrap.observability.RequestCorrelationInterceptor;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.adapters.out.crypto.ECDSASignatureVerifier;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryNonceStore;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryPublicKeyRegistry;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import es.ual.node.identitysecurity.ports.out.NonceStore;
import es.ual.node.identitysecurity.ports.out.PublicKeyRegistry;
import es.ual.node.identitysecurity.ports.out.SignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring configuration for authentication module ports and adapters. */
@Configuration
public class SecurityModuleConfiguration {

  /**
   * Provides signature verifier port implementation.
   *
   * @return signature verifier
   */
  @Bean
  public SignatureVerifier signatureVerifier() {
    return new ECDSASignatureVerifier();
  }

  /**
   * Provides validated node identity context loaded from configuration.
   *
   * @param nodeIdentityProperties node identity properties
   * @param securityProperties security properties
   * @return node identity context
   */
  @Bean
  public NodeIdentityContext nodeIdentityContext(
      final NodeIdentityProperties nodeIdentityProperties,
      final SecurityProperties securityProperties) {
    if (nodeIdentityProperties == null || securityProperties == null) {
      throw new IllegalArgumentException(
          "nodeIdentityProperties and securityProperties must not be null");
    }

    final String publicKeyBase64 =
        requireProperty(
            nodeIdentityProperties.getPublicKeyBase64(), "node.identity.public-key-base64");
    final String privateKeyBase64 =
        requireProperty(
            nodeIdentityProperties.getPrivateKeyBase64(), "node.identity.private-key-base64");
    final String keyAlgorithm =
        requireProperty(nodeIdentityProperties.getKeyAlgorithm(), "node.identity.key-algorithm");

    final PublicKey publicKey;
    final PrivateKey privateKey;
    try {
      final KeyFactory keyFactory = KeyFactory.getInstance(keyAlgorithm);
      final byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
      final byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64);
      publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
      privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
    } catch (IllegalArgumentException | GeneralSecurityException exception) {
      throw new IllegalStateException("Invalid node.identity key material", exception);
    }

    final String signatureAlgorithm =
        requireProperty(
            securityProperties.getDefaultSignatureAlgorithm(),
            "distributed.security.default-signature-algorithm");

    validateKeyPair(publicKey, privateKey, signatureAlgorithm);
    final String nodeId = deriveNodeId(publicKey);
    return new NodeIdentityContext(nodeId, publicKey, privateKey);
  }

  /**
   * Provides public key registry port implementation.
   *
   * @return public key registry
   */
  @Bean
  public PublicKeyRegistry publicKeyRegistry(
      final NodeIdentityContext nodeIdentityContext,
      final NodeIdentityProperties nodeIdentityProperties) {
    if (nodeIdentityContext == null || nodeIdentityProperties == null) {
      throw new IllegalArgumentException(
          "nodeIdentityContext and nodeIdentityProperties must not be null");
    }

    final InMemoryPublicKeyRegistry registry = new InMemoryPublicKeyRegistry();
    registry.register(nodeIdentityContext.nodeId(), nodeIdentityContext.publicKey());

    final List<String> trustedPublicKeys = nodeIdentityProperties.getTrustedPublicKeys();
    for (String trustedPublicKeyBase64 : trustedPublicKeys) {
      if (trustedPublicKeyBase64 == null || trustedPublicKeyBase64.isBlank()) {
        continue;
      }
      final PublicKey trustedPublicKey =
          decodePublicKey(trustedPublicKeyBase64.trim(), nodeIdentityProperties.getKeyAlgorithm());
      final String trustedNodeId = deriveNodeId(trustedPublicKey);
      registry.register(trustedNodeId, trustedPublicKey);
    }

    return registry;
  }

  /**
   * Provides nonce store port implementation.
   *
   * @return nonce store
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public NonceStore nonceStore() {
    return new InMemoryNonceStore();
  }

  /**
   * Provides request signature validation service.
   *
   * @param signatureVerifier signature verifier
   * @param publicKeyRegistry public key registry
   * @param nonceStore nonce store
   * @param securityProperties security properties
   * @return request signature validation service
   */
  @Bean
  public RequestSignatureValidationService requestSignatureValidationService(
      final SignatureVerifier signatureVerifier,
      final PublicKeyRegistry publicKeyRegistry,
      final NonceStore nonceStore,
      final SecurityProperties securityProperties) {
    return new RequestSignatureValidationService(
        signatureVerifier,
        publicKeyRegistry,
        nonceStore,
        securityProperties.getSignatureWindowSeconds(),
        securityProperties.getAllowedSignatureAlgorithms());
  }

  private String requireProperty(final String value, final String propertyName) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(propertyName + " must be configured");
    }
    return value.trim();
  }

  private PublicKey decodePublicKey(final String publicKeyBase64, final String keyAlgorithm) {
    try {
      final KeyFactory keyFactory = KeyFactory.getInstance(keyAlgorithm);
      final byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
      return keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
    } catch (IllegalArgumentException | GeneralSecurityException exception) {
      throw new IllegalStateException("Invalid trusted public key material", exception);
    }
  }

  private void validateKeyPair(
      final PublicKey publicKey, final PrivateKey privateKey, final String signatureAlgorithm) {
    try {
      final Signature signer = Signature.getInstance(signatureAlgorithm);
      signer.initSign(privateKey);
      signer.update("node-identity-check".getBytes(StandardCharsets.UTF_8));
      final byte[] signature = signer.sign();

      final Signature verifier = Signature.getInstance(signatureAlgorithm);
      verifier.initVerify(publicKey);
      verifier.update("node-identity-check".getBytes(StandardCharsets.UTF_8));
      if (!verifier.verify(signature)) {
        throw new IllegalStateException("node.identity key pair mismatch");
      }
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to validate node.identity key pair", exception);
    }
  }

  private String deriveNodeId(final PublicKey publicKey) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(publicKey.getEncoded());
      final StringBuilder hex = new StringBuilder();
      for (byte value : hash) {
        hex.append(String.format("%02x", value));
      }
      return "node-" + hex.substring(0, 24);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(
          "Unable to derive node identifier from public key", exception);
    }
  }

  /**
   * Provides request signature interceptor.
   *
   * @param requestSignatureValidationService validation service
   * @return request signature interceptor
   */
  @Bean
  public RequestSignatureValidator requestSignatureValidator(
      final RequestSignatureValidationService requestSignatureValidationService,
      final SecurityProperties securityProperties) {
    return new RequestSignatureValidator(
        requestSignatureValidationService, securityProperties.getDefaultSignatureAlgorithm());
  }

  /**
   * Provides request correlation interceptor.
   *
   * @return request correlation interceptor
   */
  @Bean
  public RequestCorrelationInterceptor requestCorrelationInterceptor() {
    return new RequestCorrelationInterceptor();
  }

  /**
   * Registers security interceptor into Spring MVC chain.
   *
   * @param requestSignatureValidator request signature interceptor
   * @return mvc configurer that installs interceptor
   */
  @Bean
  public WebMvcConfigurer securityWebMvcConfigurer(
      final RequestCorrelationInterceptor requestCorrelationInterceptor,
      final RequestSignatureValidator requestSignatureValidator,
      final SecurityProperties securityProperties) {
    final String[] excludedPathPatterns =
        securityProperties.getExcludedPathPatterns().stream()
            .filter(path -> path != null && !path.isBlank())
            .map(String::trim)
            .toArray(String[]::new);

    return new WebMvcConfigurer() {
      /**
       * Adds interceptor to request handling chain.
       *
       * @param registry interceptor registry
       */
      @Override
      public void addInterceptors(@NonNull final InterceptorRegistry registry) {
        registry.addInterceptor(requestCorrelationInterceptor).order(Ordered.HIGHEST_PRECEDENCE);
        final HandlerInterceptor interceptor = requestSignatureValidator;
        registry
            .addInterceptor(interceptor)
            .order(Ordered.HIGHEST_PRECEDENCE + 10)
            .excludePathPatterns(excludedPathPatterns);
      }
    };
  }
}
