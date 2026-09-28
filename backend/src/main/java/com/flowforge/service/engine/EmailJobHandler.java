package com.flowforge.service.engine;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import com.flowforge.dto.EmailStepConfig;
import com.flowforge.entity.JobType;

@Component
public class EmailJobHandler implements JobHandler {

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final String from;

    public EmailJobHandler(JavaMailSender mailSender, ObjectMapper objectMapper,
            @Value("${flowforge.mail.from}") String from) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.from = from;
    }

    @Override
    public JobType type() {
        return JobType.EMAIL;
    }

    @Override
    public boolean isSafeToRepeat(JsonNode config) {
        return false;
    }

    @Override
    public JobResult execute(JsonNode configNode, JobContext context) {
        EmailStepConfig email;
        MimeMessage message;
        try {
            email = objectMapper.treeToValue(configNode, EmailStepConfig.class);
            message = buildMessage(email);
        } catch (JacksonException | MessagingException e) {
            return new JobResult.Failure(ErrorType.INVALID_CONFIG, "Invalid email: " + e.getMessage());
        }
        try {
            mailSender.send(message);
        } catch (MailException e) {
            return classify(e);
        }

        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("sent", true);
        output.set("to", objectMapper.valueToTree(email.to()));
        output.set("cc", objectMapper.valueToTree(email.cc() != null ? email.cc() : List.of()));
        output.put("subject", email.subject());
        output.put("messageId", messageId(message));
        return new JobResult.Success(output);
    }

    private MimeMessage buildMessage(EmailStepConfig email) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        boolean hasText = email.text() != null && !email.text().isBlank();
        boolean hasHtml = email.html() != null && !email.html().isBlank();
        MimeMessageHelper helper = new MimeMessageHelper(message, hasText && hasHtml, "UTF-8");
        helper.setFrom(from);
        helper.setTo(email.to().toArray(String[]::new));
        if (email.cc() != null && !email.cc().isEmpty()) {
            helper.setCc(email.cc().toArray(String[]::new));
        }
        helper.setSubject(email.subject());
        if (hasText && hasHtml) {
            helper.setText(email.text(), email.html());
        } else if (hasHtml) {
            helper.setText(email.html(), true);
        } else {
            helper.setText(email.text(), false);
        }
        return message;
    }

    static JobResult classify(MailException error) {
        if (error instanceof MailAuthenticationException) {
            return new JobResult.Failure(ErrorType.EMAIL_REJECTED, "SMTP authentication failed");
        }
        for (Throwable cause : causesOf(error)) {
            if (cause instanceof SMTPAddressFailedException address) {
                return smtpReply(address.getReturnCode(), address.getMessage());
            }
            if (cause instanceof SMTPSendFailedException send) {
                return smtpReply(send.getReturnCode(), send.getMessage());
            }
        }
        for (Throwable cause : causesOf(error)) {
            if (cause instanceof MailConnectException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return new JobResult.Failure(ErrorType.CONNECTION_ERROR,
                        "Could not connect to the mail server: " + cause.getMessage());
            }
            if (cause instanceof SocketTimeoutException) {
                return new JobResult.Failure(ErrorType.TIMEOUT, "The mail server did not respond in time");
            }
        }
        return new JobResult.Failure(ErrorType.OUTCOME_UNKNOWN, "Sending failed: " + error.getMessage());
    }

    private static JobResult smtpReply(int code, String message) {
        String text = "SMTP " + code + ": " + (message != null ? message.trim() : "");
        return code >= 400 && code < 500
                ? new JobResult.Failure(ErrorType.EMAIL_DEFERRED, text)
                : new JobResult.Failure(ErrorType.EMAIL_REJECTED, text);
    }

    private static List<Throwable> causesOf(MailException error) {
        List<Throwable> found = new ArrayList<>();
        Set<Throwable> seen = new HashSet<>();
        Deque<Throwable> toVisit = new ArrayDeque<>();
        toVisit.add(error);
        if (error instanceof MailSendException sendException) {
            addIfPresent(toVisit, sendException.getMessageExceptions());
        }
        while (!toVisit.isEmpty()) {
            Throwable current = toVisit.poll();
            if (!seen.add(current)) {
                continue;
            }
            found.add(current);
            addIfPresent(toVisit, current.getCause());
            if (current instanceof MessagingException messaging) {
                addIfPresent(toVisit, messaging.getNextException());
            }
        }
        return found;
    }

    private static void addIfPresent(Deque<Throwable> toVisit, Throwable... errors) {
        for (Throwable error : errors) {
            if (error != null) {
                toVisit.add(error);
            }
        }
    }

    private static String messageId(MimeMessage message) {
        try {
            return message.getMessageID();
        } catch (MessagingException e) {
            return null;
        }
    }
}
