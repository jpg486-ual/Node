package es.ual.node.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.userregistration.adapters.in.web.ClientFailureRateLimitInterceptor;
import es.ual.node.userregistration.adapters.in.web.UserRoleAuthorizationInterceptor;
import es.ual.node.userregistration.adapters.in.web.UserSessionAuthenticationInterceptor;
import es.ual.node.userregistration.adapters.out.memory.InMemoryRegistrationCodePort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserAccountPort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserQuotaPort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserSessionPort;
import es.ual.node.userregistration.application.ClientFailureRateLimitProperties;
import es.ual.node.userregistration.application.UserRegistrationProperties;
import es.ual.node.userregistration.application.UserRegistrationService;
import es.ual.node.userregistration.domain.UserRole;
import es.ual.node.userregistration.ports.out.RegistrationCodePort;
import es.ual.node.userregistration.ports.out.UserAccountPort;
import es.ual.node.userregistration.ports.out.UserQuotaPort;
import es.ual.node.userregistration.ports.out.UserSessionPort;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** User registration module wiring. */
@Configuration
public class UserRegistrationModuleConfiguration {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(UserRegistrationModuleConfiguration.class);

  /**
   * Provides in-memory user account adapter.
   *
   * @return user account port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public UserAccountPort userAccountPort() {
    return new InMemoryUserAccountPort();
  }

  /**
   * Provides in-memory user quota adapter.
   *
   * @param userAccountPort user account port (resolves quotaMb)
   * @return user quota port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public UserQuotaPort userQuotaPort(final UserAccountPort userAccountPort) {
    return new InMemoryUserQuotaPort(userAccountPort);
  }

  /**
   * Provides in-memory registration code adapter.
   *
   * @return registration code port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public RegistrationCodePort registrationCodePort() {
    return new InMemoryRegistrationCodePort();
  }

  /**
   * Provides in-memory user session adapter.
   *
   * @return user session port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public UserSessionPort userSessionPort() {
    return new InMemoryUserSessionPort();
  }

  /**
   * Provides password encoder.
   *
   * @return password encoder
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Provides registration service.
   *
   * @param userAccountPort account persistence
   * @param registrationCodePort code persistence
   * @param properties registration properties
   * @param passwordEncoder password encoder
   * @param clock clock
   * @return registration service
   */
  @Bean
  public UserRegistrationService userRegistrationService(
      final UserAccountPort userAccountPort,
      final RegistrationCodePort registrationCodePort,
      final UserSessionPort userSessionPort,
      final UserRegistrationProperties properties,
      final PasswordEncoder passwordEncoder,
      final Clock clock) {
    return new UserRegistrationService(
        userAccountPort, registrationCodePort, userSessionPort, properties, passwordEncoder, clock);
  }

  /**
   * Console command to issue a registration code with quota in MB.
   *
   * @param service registration service
   * @param properties registration properties
   * @param context application context
   * @return command runner
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.user-registration",
      name = "console-issue-enabled",
      havingValue = "true")
  public ApplicationRunner registrationCodeConsoleIssuer(
      final UserRegistrationService service,
      final UserRegistrationProperties properties,
      final ConfigurableApplicationContext context) {
    return args -> {
      System.out.print("Quota MB for new user account: ");
      final BufferedReader reader =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      final String raw = reader.readLine();
      final int quotaMb;
      try {
        quotaMb = Integer.parseInt(raw.trim());
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("Invalid quota MB value");
      }

      System.out.print(
          "Role for new account [END_USER|NODE_ADMIN|SUPERNODE_ADMIN] (default END_USER): ");
      final String rawRole = reader.readLine();
      final UserRole role =
          rawRole == null || rawRole.isBlank() ? UserRole.END_USER : UserRole.parse(rawRole);

      final String code = service.issueRegistrationCode(quotaMb, role);
      System.out.println("Registration code generated: " + code);
      System.out.println("Assigned role: " + role.name());
      System.out.println("Use it in POST /auth/register with username/password.");

      if (properties.isConsoleExitAfterIssue()) {
        LOGGER.info("Exiting node after console registration code issuance");
        context.close();
      }
    };
  }

  /**
   * Provides session authentication interceptor.
   *
   * @param service registration/auth service
   * @return user session interceptor
   */
  @Bean
  public UserSessionAuthenticationInterceptor userSessionAuthenticationInterceptor(
      final UserRegistrationService service) {
    return new UserSessionAuthenticationInterceptor(service);
  }

  /** Provides role-based authorization interceptor. */
  @Bean
  public UserRoleAuthorizationInterceptor userRoleAuthorizationInterceptor(
      final ObjectMapper objectMapper) {
    return new UserRoleAuthorizationInterceptor(objectMapper);
  }

  /**
   * Provides optional client failure rate limiter interceptor.
   *
   * @param properties limiter properties
   * @return client failure rate limiter interceptor
   */
  @Bean
  public ClientFailureRateLimitInterceptor clientFailureRateLimitInterceptor(
      final ClientFailureRateLimitProperties properties) {
    return new ClientFailureRateLimitInterceptor(properties);
  }

  /**
   * Registers user session interceptor for client-facing APIs.
   *
   * @param userSessionAuthenticationInterceptor session interceptor
   * @return mvc configurer
   */
  @Bean
  public WebMvcConfigurer userSessionWebMvcConfigurer(
      final UserSessionAuthenticationInterceptor userSessionAuthenticationInterceptor,
      final UserRoleAuthorizationInterceptor userRoleAuthorizationInterceptor,
      final ClientFailureRateLimitInterceptor clientFailureRateLimitInterceptor) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(@NonNull final InterceptorRegistry registry) {
        registry
            .addInterceptor(userSessionAuthenticationInterceptor)
            .addPathPatterns("/fs/**", "/files/**", "/sync/**", "/ops/**")
            .excludePathPatterns("/auth/**");
        registry
            .addInterceptor(userRoleAuthorizationInterceptor)
            .addPathPatterns("/fs/**", "/files/**", "/sync/**", "/ops/**")
            .excludePathPatterns("/auth/**");
        registry
            .addInterceptor(clientFailureRateLimitInterceptor)
            .addPathPatterns("/fs/**", "/files/**", "/sync/**")
            .excludePathPatterns("/auth/**");
      }
    };
  }
}
