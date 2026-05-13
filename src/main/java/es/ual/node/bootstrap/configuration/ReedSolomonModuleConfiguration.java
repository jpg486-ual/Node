package es.ual.node.bootstrap.configuration;

import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsIntegrityVerifier;
import es.ual.node.reedsolomon.ports.out.RsDecoderPort;
import es.ual.node.reedsolomon.ports.out.RsEncoderPort;
import es.ual.node.reedsolomon.ports.out.RsIntegrityVerifierPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for Reed-Solomon runtime ports. */
@Configuration
public class ReedSolomonModuleConfiguration {

  /**
   * Provides shared in-memory RS codec adapter.
   *
   * @return codec adapter
   */
  @Bean
  public InMemoryRsCodecAdapter inMemoryRsCodecAdapter() {
    return new InMemoryRsCodecAdapter();
  }

  /**
   * Provides RS encoder port.
   *
   * @param adapter codec adapter
   * @return encoder port
   */
  @Bean
  public RsEncoderPort rsEncoderPort(
      @Qualifier("inMemoryRsCodecAdapter") final InMemoryRsCodecAdapter adapter) {
    return adapter;
  }

  /**
   * Provides RS decoder port.
   *
   * @param adapter codec adapter
   * @return decoder port
   */
  @Bean
  public RsDecoderPort rsDecoderPort(
      @Qualifier("inMemoryRsCodecAdapter") final InMemoryRsCodecAdapter adapter) {
    return adapter;
  }

  /**
   * Provides RS integrity verifier port.
   *
   * @return integrity verifier port
   */
  @Bean
  public RsIntegrityVerifierPort rsIntegrityVerifierPort() {
    return new InMemoryRsIntegrityVerifier();
  }
}
