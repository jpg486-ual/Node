package es.ual.node.userregistration.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** User registration behavior properties. */
@ConfigurationProperties(prefix = "node.user-registration")
public class UserRegistrationProperties {

  private int codeLength = 8;
  private long codeTtlMinutes = 30L;
  private long sessionTtlMinutes = 10080L;
  private boolean consoleIssueEnabled = false;
  private boolean consoleExitAfterIssue = true;

  public int getCodeLength() {
    return codeLength;
  }

  public void setCodeLength(final int codeLength) {
    this.codeLength = codeLength;
  }

  public long getCodeTtlMinutes() {
    return codeTtlMinutes;
  }

  public void setCodeTtlMinutes(final long codeTtlMinutes) {
    this.codeTtlMinutes = codeTtlMinutes;
  }

  public long getSessionTtlMinutes() {
    return sessionTtlMinutes;
  }

  public void setSessionTtlMinutes(final long sessionTtlMinutes) {
    this.sessionTtlMinutes = sessionTtlMinutes;
  }

  public boolean isConsoleIssueEnabled() {
    return consoleIssueEnabled;
  }

  public void setConsoleIssueEnabled(final boolean consoleIssueEnabled) {
    this.consoleIssueEnabled = consoleIssueEnabled;
  }

  public boolean isConsoleExitAfterIssue() {
    return consoleExitAfterIssue;
  }

  public void setConsoleExitAfterIssue(final boolean consoleExitAfterIssue) {
    this.consoleExitAfterIssue = consoleExitAfterIssue;
  }
}
