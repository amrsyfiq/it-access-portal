package com.itaccess.portal.service;

import com.itaccess.portal.messaging.event.AccessRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Builds and sends the notification emails. Locally they go to Mailpit (SMTP localhost:1025), which
 * shows them in a web inbox at http://localhost:8025 instead of delivering them.
 *
 * Recipients:
 *  - AccessRequestSubmitted: the administrators (portal.notifications.mail.admin-address)
 *  - AccessRequestReviewed:  the person who submitted the request, as username@user-domain
 *    (in gateway mode the "username" is the WSO2 user id, so the address is <id>@user-domain)
 */
@Component
public class NotificationMailer {

    private static final Logger log = LoggerFactory.getLogger(NotificationMailer.class);
    private static final String PORTAL_URL = "http://localhost:4200/requests/";

    private final JavaMailSender mailSender;
    private final String from;
    private final String adminAddress;
    private final String userDomain;

    public NotificationMailer(JavaMailSender mailSender,
                              @Value("${portal.notifications.mail.from}") String from,
                              @Value("${portal.notifications.mail.admin-address}") String adminAddress,
                              @Value("${portal.notifications.mail.user-domain}") String userDomain) {
        this.mailSender = mailSender;
        this.from = from;
        this.adminAddress = adminAddress;
        this.userDomain = userDomain;
    }

    /**
     * @param submittedBy who created the request (from its history); the recipient of "reviewed" emails
     * @throws org.springframework.mail.MailException if the SMTP server rejects or cannot be reached
     */
    public void send(AccessRequestEvent event, String submittedBy) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        if (AccessRequestEvent.SUBMITTED.equals(event.eventType())) {
            mail.setTo(adminAddress);
            mail.setSubject("New access request #%d: %s".formatted(event.requestId(), event.systemName()));
            mail.setText("""
                    %s has requested access to %s.

                    Review it here: %s%d
                    """.formatted(event.actor(), event.systemName(), PORTAL_URL, event.requestId()));
        } else {
            mail.setTo(addressOf(submittedBy));
            mail.setSubject("Your access request #%d was %s".formatted(event.requestId(),
                    event.status().name().toLowerCase()));
            mail.setText("""
                    Your request for access to %s was %s by %s.

                    Details: %s%d
                    """.formatted(event.systemName(), event.status().name().toLowerCase(), event.actor(),
                    PORTAL_URL, event.requestId()));
        }
        mailSender.send(mail);
        log.info("Email sent to {}: {}", String.join(", ", mail.getTo()), mail.getSubject());
    }

    String addressOf(String username) {
        return username.contains("@") ? username : username + "@" + userDomain;
    }
}
