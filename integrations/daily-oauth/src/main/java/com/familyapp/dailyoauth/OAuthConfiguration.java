package com.familyapp.dailyoauth;

import java.time.Duration;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 协议、同意和撤销交由框架；应用账号只读，授权存储独立。 */
@Configuration
@EnableWebSecurity
public class OAuthConfiguration {
    private final Object grantLock=new Object();
    @Bean PasswordEncoder encoder(){return new BCryptPasswordEncoder();}

    @Bean UserDetailsService users(JdbcTemplate jdbc) {
        return email -> jdbc.query("SELECT id,password_hash FROM public.users WHERE email=? AND deleted_at IS NULL AND password_hash IS NOT NULL",
            (rs,n)->User.withUsername(rs.getString("id")).password(rs.getString("password_hash")).roles("DAILY_USER").build(),email)
            .stream().findFirst().orElseThrow(()->new UsernameNotFoundException("登录失败"));
    }

    @Bean RegisteredClientRepository clients(JdbcTemplate jdbc,PasswordEncoder encoder,
        @Value("${daily.client-id}") String client,@Value("${daily.client-secret}") String secret,
        @Value("${daily.redirect-uri}") String redirect,@Value("${daily.reader-id}") String reader,
        @Value("${daily.reader-secret}") String readerSecret) {
        URI uri=URI.create(redirect);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getFragment()!=null
            || uri.getUserInfo()!=null || secret.length()<32 || readerSecret.length()<32 || reader.equals(client))
            throw new IllegalArgumentException("授权客户端配置无效");
        var repo=new JdbcRegisteredClientRepository(jdbc);
        if(repo.findByClientId(client)==null)repo.save(RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(client).clientName("ChatGPT 每日只读")
            .clientSecret(encoder.encode(secret)).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN).redirectUri(redirect).scope("daily:read")
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(Duration.ofMinutes(10)).refreshTokenTimeToLive(Duration.ofDays(7))
                .reuseRefreshTokens(false).build()).build());
        if(repo.findByClientId(reader)==null)repo.save(RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(reader).clientSecret(encoder.encode(readerSecret))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("introspect")
            .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE).build()).build());
        if(!encoder.matches(secret,repo.findByClientId(client).getClientSecret())
            || !repo.findByClientId(client).getRedirectUris().equals(java.util.Set.of(redirect))
            || !encoder.matches(readerSecret,repo.findByClientId(reader).getClientSecret()))
            throw new IllegalStateException("已登记的客户端与配置不一致，请先审核轮换方案");
        return repo;
    }

    @Bean OAuth2AuthorizationService authorizations(JdbcTemplate jdbc,RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc,clients);
    }
    @Bean OAuth2AuthorizationConsentService consents(JdbcTemplate jdbc,RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc,clients);
    }
    @Bean AuthorizationServerSettings settings(@Value("${daily.issuer}") String issuer) {
        URI uri=URI.create(issuer);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
            || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException("签发者配置无效");
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }
    @Bean OAuth2TokenGenerator<?> tokens(JdbcTemplate jdbc,@Value("${daily.resource}") String resource) {
        if(!resource.startsWith("https://"))throw new IllegalArgumentException("资源须为 HTTPS");
        var access=new OAuth2AccessTokenGenerator();
        access.setAccessTokenCustomizer(context->{
            if(context.getAuthorizedScopes().contains("daily:read")) {
                String id=context.getPrincipal().getName();
                if(!id.matches("[1-9][0-9]{0,18}") || jdbc.queryForObject(
                        "SELECT COUNT(*) FROM public.users WHERE id=? AND deleted_at IS NULL",Long.class,Long.parseLong(id))!=1)
                    throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
                context.getClaims().audience(new java.util.ArrayList<>(List.of(resource))).subject(id);
            }
        });
        return new DelegatingOAuth2TokenGenerator(access,new OAuth2RefreshTokenGenerator());
    }

    @Bean @Order(1) SecurityFilterChain oauth(HttpSecurity http,@Value("${daily.resource}") String resource,
        @Value("${daily.issuer}") String issuer) throws Exception {
        var oauth=OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(oauth.getEndpointsMatcher()).with(oauth,c->c.authorizationEndpoint(
                endpoint->endpoint.consentPage(issuer+"/consent")))
            .authorizeHttpRequests(c->c.anyRequest().authenticated())
            .exceptionHandling(c->c.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(issuer+"/login")))
            .addFilterBefore(new ResourceParameterFilter(resource),org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .addFilterBefore(new GrantSerializationFilter(grantLock),org.springframework.security.web.context.SecurityContextHolderFilter.class);
        return http.build();
    }
    @Bean @Order(2) SecurityFilterChain browser(HttpSecurity http,@Value("${daily.issuer}") String issuer) throws Exception {
        var success=new org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler();
        success.setRedirectStrategy((request,response,url)->{
            URI target=URI.create(url);String path=target.getPath();String prefix=URI.create(issuer).getPath();
            if(path.startsWith(prefix+"/"))path=path.substring(prefix.length());
            response.sendRedirect(issuer+path+(target.getRawQuery()==null?"":"?"+target.getRawQuery()));
        });
        http.authorizeHttpRequests(c->c.requestMatchers("/login","/error").permitAll().anyRequest().authenticated())
            .formLogin(c->c.loginPage("/login").successHandler(success).permitAll())
            .addFilterBefore(new LoginThrottle(),UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new GrantSerializationFilter(grantLock),org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .logout(c->c.logoutSuccessUrl("/login"));
        return http.build();
    }
}
