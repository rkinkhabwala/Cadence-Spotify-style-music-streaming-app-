package com.cadence.common.security;

import com.cadence.common.web.ApiPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Stateless JWT resource server (spec 6). Route rules for every context live here so the whole access
 * policy can be reviewed in one place; method-level {@code @PreAuthorize} and domain checks add to it.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    static final String ROLES_CLAIM = "roles";

    private static final String V1 = ApiPaths.V1;

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder jwtDecoder, ObjectMapper objectMapper,
                                    @Value("${cadence.web.allowed-origins}") List<String> webOrigins) throws Exception {
        ProblemSecurityHandlers problems = new ProblemSecurityHandlers(objectMapper);
        http
                .cors(cors -> cors.configurationSource(corsConfiguration(webOrigins)))
                .csrf(AbstractHttpConfigurer::disable)          // bearer tokens only, no cookies (yet)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // identity
                        .requestMatchers(HttpMethod.POST, V1 + "/auth/register", V1 + "/auth/login",
                                V1 + "/auth/refresh", V1 + "/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        // catalog: public reads (the Range stream endpoint /tracks/{id}/stream stays authenticated)
                        .requestMatchers(HttpMethod.GET, V1 + "/artists/**", V1 + "/albums/**", V1 + "/tracks/*",
                                V1 + "/genres").permitAll()
                        // streaming: playlists are authorized by the signed playback token in their URL
                        .requestMatchers(HttpMethod.GET, V1 + "/playback/*/master.m3u8", V1 + "/playback/*/*/index.m3u8").permitAll()
                        // dev-only hls.js test page (the handler exists only with the dev profile)
                        .requestMatchers(HttpMethod.GET, "/dev/**").permitAll()
                        // admin
                        .requestMatchers(V1 + "/admin/**").hasRole("ADMIN")
                        // ops and docs
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }

    /**
     * Spec 6: CORS restricted to the web client's origin(s). The dev server and the web container proxy the API
     * (same origin), so this matters only when the client is served from elsewhere. Bearer tokens, no cookies.
     */
    static CorsConfigurationSource corsConfiguration(List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, HttpHeaders.IF_MATCH));
        cors.setExposedHeaders(List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        source.registerCorsConfiguration("/.well-known/**", cors);
        return source;
    }

    private static JwtAuthenticationConverter authenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    WebMvcConfigurer currentUserResolver() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentUserArgumentResolver());
            }
        };
    }
}
