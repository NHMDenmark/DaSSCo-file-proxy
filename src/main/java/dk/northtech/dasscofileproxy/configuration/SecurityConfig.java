package dk.northtech.dasscofileproxy.configuration;

import dk.northtech.dasscofileproxy.configuration.httpheaders.CorsHeaders;
import dk.northtech.dasscofileproxy.configuration.httpheaders.CspHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@EnableWebSecurity
@Configuration
public class SecurityConfig {

  private final CorsHeaders corsHeaders;
  private final CspHeaders cspHeaders;

  public SecurityConfig(CorsHeaders corsHeaders, CspHeaders cspHeaders) {
    this.corsHeaders = corsHeaders;
    this.cspHeaders = cspHeaders;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    JwtAuthenticationConverter keycloakJwtAuthenticationConverter = new JwtAuthenticationConverter();
    keycloakJwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(
            (Jwt jwt) ->
                    jwt.<Map<String, List<String>>>getClaim("realm_access")
                            .getOrDefault("roles", Collections.emptyList())
                            .stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .collect(Collectors.toList())
    );

    http
            // Default is to use a CorsConfigurationSource bean (provided below).
            .httpBasic().disable()
            .sessionManagement(
                    sessionManagement ->
                            sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .oauth2ResourceServer(
                    httpSecurityOAuth2ResourceServerConfigurer ->
                            httpSecurityOAuth2ResourceServerConfigurer
                                    .jwt()
                                    .jwtAuthenticationConverter(keycloakJwtAuthenticationConverter)
            )
            .authorizeRequests(
                    authorizeRequestsCustomizer ->
                            authorizeRequestsCustomizer
                                    // can use .authenticated() and do the role check on each JAX-RS method
                                    .anyRequest().permitAll() // use this until we know what to lock
            )
            .cors()
            .and()
            .headers()
            .addHeaderWriter(this.cspHeaders::writeHeaders);

    // For now, disable CSRF token validation.
    http.csrf().disable();

    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", this.corsHeaders.getCorsConfiguration());
    return source;
  }

  @Bean("no-auth")
  public SecurityFilterChain noFilterChain(HttpSecurity http) throws Exception {
    // For now, we do not require any credentials.
    return http.build();
  }
}
