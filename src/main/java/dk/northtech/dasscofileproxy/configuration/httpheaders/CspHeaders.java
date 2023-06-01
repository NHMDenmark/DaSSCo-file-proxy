package dk.northtech.dasscofileproxy.configuration.httpheaders;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This class implements a "HeaderWriter" (as functional interface) based on the application properties, to be used
 * with Spring Security's addHeaderWriter method.
 */
@ConfigurationProperties("csp")
public class CspHeaders {
  public final String ContentSecurityPolicy;
  public final String ContentSecurityPolicyReportOnly;

  public CspHeaders(String contentSecurityPolicy, String contentSecurityPolicyReportOnly) {
    ContentSecurityPolicy = trimToNull(contentSecurityPolicy);
    ContentSecurityPolicyReportOnly = trimToNull(contentSecurityPolicyReportOnly);
  }

  public void writeHeaders(HttpServletRequest req, HttpServletResponse res) {
    if (this.ContentSecurityPolicy != null) {
      res.setHeader("Content-Security-Policy", this.ContentSecurityPolicy);
    }
    if (this.ContentSecurityPolicyReportOnly != null) {
      res.setHeader("Content-Security-Policy-Report-Only", this.ContentSecurityPolicyReportOnly);
    }
  }

  private String trimToNull(String value) {
    return value == null || value.isBlank()
      ? null
      : value.trim();
  }
}
