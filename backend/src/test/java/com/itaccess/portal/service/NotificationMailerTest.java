package com.itaccess.portal.service;

import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationMailerTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");

    @Mock
    private JavaMailSender mailSender;

    private NotificationMailer mailer;

    @BeforeEach
    void setUp() {
        mailer = new NotificationMailer(mailSender, "IT Access Portal <no-reply@itaccess.example>",
                "it-admins@example.com", "example.com");
    }

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        return mail.getValue();
    }

    @Test
    void submittedRequestIsSentToTheAdmins() {
        mailer.send(AccessRequestEvent.submitted(55L, "GitLab", "ted.l", NOW), null);

        SimpleMailMessage mail = sent();
        assertThat(mail.getTo()).containsExactly("it-admins@example.com");
        assertThat(mail.getFrom()).isEqualTo("IT Access Portal <no-reply@itaccess.example>");
        assertThat(mail.getSubject()).isEqualTo("New access request #55: GitLab");
        assertThat(mail.getText()).contains("ted.l has requested access to GitLab")
                .contains("http://localhost:4200/requests/55");
    }

    @Test
    void reviewedRequestIsSentToTheSubmitter() {
        mailer.send(AccessRequestEvent.reviewed(55L, "GitLab", RequestStatus.REJECTED, "alice.admin", NOW), "ted.l");

        SimpleMailMessage mail = sent();
        assertThat(mail.getTo()).containsExactly("ted.l@example.com");
        assertThat(mail.getSubject()).isEqualTo("Your access request #55 was rejected");
        assertThat(mail.getText()).contains("was rejected by alice.admin");
    }

    @Test
    void usernameThatIsAlreadyAnAddressIsUsedAsIs() {
        assertThat(mailer.addressOf("ted@corp.example")).isEqualTo("ted@corp.example");
        assertThat(mailer.addressOf("ted.l")).isEqualTo("ted.l@example.com");
    }
}
