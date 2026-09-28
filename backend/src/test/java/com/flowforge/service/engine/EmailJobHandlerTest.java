package com.flowforge.service.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.List;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.flowforge.IntegrationTest;
import com.flowforge.MailpitContainer;
import com.flowforge.MailpitContainer.Message;

@IntegrationTest
class EmailJobHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    EmailJobHandler handler;

    @Autowired
    MailpitContainer mailpit;

    @BeforeEach
    void emptyMailbox() {
        mailpit.deleteAllMessages();
    }

    @Test
    void deliversAMessageWithTextAndHtml() {
        JobResult result = handler.execute(json("""
                {
                  "to": ["jane@example.com", "ali@example.com"],
                  "cc": ["finance@example.com"],
                  "subject": "Daily sales: 124.50 EUR",
                  "text": "Revenue today: 124.50 EUR",
                  "html": "<p>Revenue today: <b>124.50 EUR</b></p>"
                }
                """), context());

        assertThat(result).isInstanceOfSatisfying(JobResult.Success.class, success -> {
            assertThat(success.output().get("sent").booleanValue()).isTrue();
            assertThat(success.output().get("messageId").asString()).isNotBlank();
        });
        assertThat(mailpit.messages()).singleElement().satisfies(message -> {
            assertThat(message.subject()).isEqualTo("Daily sales: 124.50 EUR");
            assertThat(message.to()).containsExactly("jane@example.com", "ali@example.com");
            assertThat(message.cc()).containsExactly("finance@example.com");
            assertThat(message.from()).isEqualTo("flowforge@localhost");
            assertThat(message.text()).isEqualTo("Revenue today: 124.50 EUR");
            assertThat(message.html()).contains("<b>124.50 EUR</b>");
        });
    }

    @Test
    void deliversATextOnlyMessage() {
        handler.execute(json("""
                { "to": ["ops@example.com"], "subject": "Health check failed", "text": "The API is down." }
                """), context());

        List<Message> messages = mailpit.messages();
        assertThat(messages).extracting(Message::text).containsExactly("The API is down.");
        assertThat(messages).extracting(Message::html).containsExactly("");
    }

    @Test
    void anUnreachableMailServerIsAConnectionErrorBecauseNothingWasSent() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        JavaMailSenderImpl unreachable = new JavaMailSenderImpl();
        unreachable.setHost("localhost");
        unreachable.setPort(closedPort);

        JobResult result = new EmailJobHandler(unreachable, mapper, "flowforge@localhost").execute(json("""
                { "to": ["ops@example.com"], "subject": "x", "text": "x" }
                """), context());

        assertThat(result).isInstanceOfSatisfying(JobResult.Failure.class,
                failure -> assertThat(failure.type()).isEqualTo(ErrorType.CONNECTION_ERROR));
    }

    @Test
    void permanentAndTemporarySmtpRejectionsAreClassifiedByReplyCode() throws Exception {
        assertThat(EmailJobHandler.classify(sendFailure(550, "5.1.1 Mailbox does not exist")))
                .isInstanceOfSatisfying(JobResult.Failure.class, failure -> {
                    assertThat(failure.type()).isEqualTo(ErrorType.EMAIL_REJECTED);
                    assertThat(failure.message()).startsWith("SMTP 550:");
                });
        assertThat(EmailJobHandler.classify(sendFailure(451, "4.7.1 Greylisted, try again later")))
                .isInstanceOfSatisfying(JobResult.Failure.class,
                        failure -> assertThat(failure.type()).isEqualTo(ErrorType.EMAIL_DEFERRED));
    }

    @Test
    void anUnclearFailureWhileSendingIsAnUnknownOutcome() {
        assertThat(EmailJobHandler.classify(new MailSendException("Connection reset during DATA",
                new MessagingException("Exception reading response", new SocketTimeoutException("Read timed out")))))
                .isEqualTo(new JobResult.Failure(ErrorType.TIMEOUT, "The mail server did not respond in time"));
        assertThat(EmailJobHandler.classify(new MailSendException("Connection reset during DATA")))
                .isInstanceOfSatisfying(JobResult.Failure.class,
                        failure -> assertThat(failure.type()).isEqualTo(ErrorType.OUTCOME_UNKNOWN));
        assertThat(EmailJobHandler.classify(new MailAuthenticationException("535 bad credentials")))
                .isEqualTo(new JobResult.Failure(ErrorType.EMAIL_REJECTED, "SMTP authentication failed"));
    }

    @Test
    void anEmailIsNeverRepeatedAfterAnUnknownOutcome() {
        assertThat(handler.isSafeToRepeat(json("{}"))).isFalse();
        assertThat(RetryPolicy.isRetryable(ErrorType.OUTCOME_UNKNOWN, false)).isFalse();
        assertThat(RetryPolicy.isRetryable(ErrorType.TIMEOUT, false)).isFalse();
        assertThat(RetryPolicy.isRetryable(ErrorType.CONNECTION_ERROR, false)).isTrue();
        assertThat(RetryPolicy.isRetryable(ErrorType.EMAIL_DEFERRED, false)).isTrue();
    }

    private static MailSendException sendFailure(int code, String reply) throws Exception {
        SMTPAddressFailedException rejected = new SMTPAddressFailedException(
                new InternetAddress("nobody@example.com"), "RCPT TO:<nobody@example.com>", code, reply);
        MessagingException sendFailed = new MessagingException("Invalid Addresses", rejected);
        return new MailSendException("Failed messages", sendFailed);
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    private static JobContext context() {
        return new JobContext(301, 1, 30, null);
    }
}
