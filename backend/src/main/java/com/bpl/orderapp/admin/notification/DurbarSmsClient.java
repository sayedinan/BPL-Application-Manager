package com.bpl.orderapp.admin.notification;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper around Durbar's HTTP SMS gateway
 * ({@code POST /httpapi/sendsms}).
 *
 * <p>POST (not GET, even though Durbar's API offers both) is used
 * deliberately — GET would put {@code userId}/{@code password} in
 * the request URL, which ends up in server access logs; POST keeps
 * credentials in the body.
 *
 * <p>Only instantiated by {@code NotificationConfig} when {@code
 * durbar.sms.user-id} is actually set — see that class for the same
 * "optional module" reasoning already used for {@code
 * EmailNotificationService}.
 *
 * <p>Not {@code @Async} itself — {@link SmsNotificationService},
 * which calls this per recipient, carries the {@code @Async}
 * annotation instead, same division of responsibility as {@code
 * EmailNotificationService}/{@code sendToAll}.
 */
public class DurbarSmsClient {

    private final RestClient restClient;
    private final String userId;
    private final String password;

    public DurbarSmsClient(RestClient restClient, String userId, String password) {
        this.restClient = restClient;
        this.userId = userId;
        this.password = password;
    }

    /**
     * @param phoneNumber Durbar-format number, e.g. "8801791027113" —
     *                    callers pass the value straight from the
     *                    {@code users.phone_number} column, no
     *                    conversion needed (see V15 migration notes)
     */
    public SmsSendResult send(String phoneNumber, String smsText) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("userId", userId);
        form.add("password", password);
        form.add("smsText", smsText);
        form.add("commaSeperatedReceiverNumbers", phoneNumber);

        try {
            SmsSendResult result = restClient.post()
                .uri("/httpapi/sendsms")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(SmsSendResult.class);
            return result != null ? result
                : new SmsSendResult(true, null, "Empty response body from Durbar");
        } catch (Exception e) {
            return new SmsSendResult(true, null, "Request to Durbar failed: " + e.getMessage());
        }
    }

    /**
     * Mirrors Durbar's sendsms JSON response exactly:
     * {@code {"insertedSmsIds": "...", "message": "Success!", "isError": false}}.
     * Field names/casing match the API's own — not renamed to Java
     * convention — so no {@code @JsonProperty} mapping is needed.
     */
    public record SmsSendResult(boolean isError, String insertedSmsIds, String message) {}
}
