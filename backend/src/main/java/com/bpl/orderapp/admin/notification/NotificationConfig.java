package com.bpl.orderapp.admin.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Wires the notification module's beans — but only when Gmail
 * credentials are actually configured.
 *
 * <p>Spring Boot auto-configures a {@code JavaMailSender} bean
 * whenever {@code spring.mail.host} is set, regardless of whether
 * username/password are present — {@code spring.mail.host} is
 * always set to {@code smtp.gmail.com} in application.yml, so that
 * bean always exists. It would just fail with an authentication
 * error if actually used with no credentials. This class is what
 * stops it from ever being used in that case: {@link
 * EmailNotificationService} and {@link NotificationRecipientResolver}
 * are only created here, and nothing else in the codebase talks to
 * {@code JavaMailSender} directly.
 *
 * <p>{@code @ConditionalOnProperty} alone isn't quite right here —
 * its default match rule is "matches unless the value equals
 * 'false'", so an empty string (the default set in application.yml,
 * {@code ${MAIL_USERNAME:}}) would still count as present. This
 * uses a SpEL expression instead, which explicitly checks for a
 * non-empty string after the placeholder is resolved.
 *
 * <p>Follows the same manual {@code @Bean}-wiring pattern as {@code
 * PasswordEncoderConfig} (this codebase does not use
 * {@code @Component} on service classes).
 */
@Configuration
@ConditionalOnExpression("'${spring.mail.username:}' != ''")
public class NotificationConfig {

    @Bean
    public NotificationRecipientResolver notificationRecipientResolver(JdbcTemplate jdbc) {
        return new NotificationRecipientResolver(jdbc);
    }

    @Bean
    public EmailNotificationService emailNotificationService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}") String fromAddress,
            NotificationRecipientResolver recipientResolver) {
        return new EmailNotificationService(mailSender, fromAddress, recipientResolver);
    }

    @Bean
    @ConditionalOnExpression("'${durbar.sms.user-id:}' != ''")
    public DurbarSmsClient durbarSmsClient(
            @Value("${durbar.sms.base-url}") String baseUrl,
            @Value("${durbar.sms.user-id}") String userId,
            @Value("${durbar.sms.password}") String password) {
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        return new DurbarSmsClient(restClient, userId, password);
    }

    @Bean
    @ConditionalOnExpression("'${durbar.sms.user-id:}' != ''")
    public SmsNotificationService smsNotificationService(
            DurbarSmsClient durbarSmsClient,
            JdbcTemplate jdbc,
            NotificationRecipientResolver recipientResolver,
            Optional<EmailNotificationService> emailNotificationService) {
        return new SmsNotificationService(durbarSmsClient, jdbc, recipientResolver, emailNotificationService);
    }
}
