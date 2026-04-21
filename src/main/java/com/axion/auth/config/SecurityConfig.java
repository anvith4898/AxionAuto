package com.axion.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.axion.auth.security.AuthTokenAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthTokenAuthenticationFilter authTokenAuthenticationFilter;

    public SecurityConfig(AuthTokenAuthenticationFilter authTokenAuthenticationFilter) {
        this.authTokenAuthenticationFilter = authTokenAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/webhooks/**").permitAll()
                .requestMatchers("/actuator/**").permitAll() // Typically actuator is secured or separated, but good for local dev
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/oauth/instagram/callback").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(authTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
