package com.bpl.orderapp.admin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SessionConfig {

    /** Request attribute set by AuthController when "remember me" is ticked. */
    public static final String REMEMBER_ME_ATTR = "BPL_REMEMBER_ME";

    @Bean
    public CookieSerializer cookieSerializer(
            @Value("${spring.session.cookie.secure:false}") boolean secure,
            @Value("${spring.session.cookie.same-site:Strict}") String sameSite) {
        DefaultCookieSerializer s = new DefaultCookieSerializer();
        s.setCookieName("SESSION");
        s.setCookiePath("/");
        s.setUseHttpOnlyCookie(true);
        s.setUseSecureCookie(secure);
        s.setSameSite(sameSite);
        // When this request attribute is present, the cookie is written
        // as persistent (survives browser restart); otherwise it stays
        // a session cookie.
        s.setRememberMeRequestAttribute(REMEMBER_ME_ATTR);
        return s;
    }
}