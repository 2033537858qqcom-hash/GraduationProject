package cn.zzuit.campus.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionCsrfTokenRepository csrfRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean AuthenticationManager authenticationManager(IdentityService identities, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(identities);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts,
                                 HttpSessionCsrfTokenRepository csrf,IdentityService identities) throws Exception {
        return http
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .securityContext(context -> context.securityContextRepository(contexts))
            .csrf(config -> config.csrfTokenRepository(csrf))
            .addFilterBefore(new LiveIdentityFilter(identities,contexts),AuthorizationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**", "/api/health", "/api/auth/csrf", "/api/auth/login", "/error").permitAll()
                .requestMatchers("/api/portal/**").hasAuthority("portal:read")
                .requestMatchers("/api/workbench/**").hasAuthority("workbench:read")
                .requestMatchers("/api/admin/foundation/**").hasAuthority("foundation:manage")
                .requestMatchers("/api/admin/resources", "/api/admin/resources/**").hasAuthority("foundation:manage")
                .requestMatchers("/api/admin/counselors", "/api/admin/counselors/**").hasAuthority("foundation:manage")
                .requestMatchers("/api/admin/repair-settings", "/api/admin/repair-settings/**").hasAuthority("repair:configure")
                .requestMatchers("/api/leave/mine", "/api/leave/mine/**").hasAuthority("leave:apply")
                .requestMatchers("/api/leave/review", "/api/leave/review/**").hasAuthority("leave:review")
                .requestMatchers("/api/certificates/mine", "/api/certificates/mine/**").hasAuthority("certificate:apply")
                .requestMatchers("/api/certificates/review", "/api/certificates/review/**").hasAuthority("certificate:review")
                .requestMatchers("/api/affairs/mine").hasAuthority("portal:read")
                .requestMatchers("/api/repairs/mine", "/api/repairs/mine/**").hasAuthority("repair:apply")
                .requestMatchers("/api/repairs/dispatch", "/api/repairs/dispatch/**").hasAuthority("repair:dispatch")
                .requestMatchers("/api/repairs/work", "/api/repairs/work/**").hasAuthority("repair:work")
                .requestMatchers("/api/assistant/**").hasAuthority("agent:query")
                .requestMatchers("/api/admin/content", "/api/admin/content/**").hasAnyAuthority("content:edit", "content:review")
                .requestMatchers("/api/admin/**").denyAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"AUTH_REQUIRED\",\"message\":\"请先登录\"}");
                })
                .accessDeniedHandler((request, response, ex) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"无操作权限或会话校验已过期，请刷新后重试\"}");
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true).deleteCookies("CAMPUS_SESSION")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .build();
    }
}
