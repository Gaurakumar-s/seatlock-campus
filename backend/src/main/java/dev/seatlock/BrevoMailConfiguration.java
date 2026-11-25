package dev.seatlock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** HTTPS delivery for hosts that block outbound SMTP ports. */
@Configuration
public class BrevoMailConfiguration {
  @Bean
  @ConditionalOnProperty(name = "seatlock.mail-transport", havingValue = "brevo")
  JavaMailSender brevoMailSender(
      ObjectMapper json, @Value("${seatlock.brevo-api-key:}") String key) {
    if (key.isBlank())
      throw new IllegalStateException("BREVO_API_KEY is required for Brevo delivery");
    return new BrevoSender(
        json,
        key,
        URI.create("https://api.brevo.com/v3/smtp/email"),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  static final class BrevoSender extends JavaMailSenderImpl {
    private final ObjectMapper json;
    private final String key;
    private final URI endpoint;
    private final HttpClient client;

    BrevoSender(ObjectMapper json, String key, URI endpoint, HttpClient client) {
      this.json = json;
      this.key = key;
      this.endpoint = endpoint;
      this.client = client;
    }

    @Override
    public void send(SimpleMailMessage message) {
      try {
        if (message.getTo() == null || message.getTo().length != 1 || message.getFrom() == null)
          throw new MailSendException("Verification email requires one recipient and a sender");
        String payload =
            json.writeValueAsString(
                Map.of(
                    "sender", Map.of("name", "SeatLock Campus", "email", message.getFrom()),
                    "to", List.of(Map.of("email", message.getTo()[0])),
                    "subject", message.getSubject(),
                    "textContent", message.getText()));
        HttpRequest request =
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("api-key", key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // Do not return provider response bodies: they can contain account or recipient details.
        if (response.statusCode() != 201)
          throw new MailSendException("Email provider rejected the delivery request");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new MailSendException("Email delivery was interrupted");
      } catch (IOException e) {
        throw new MailSendException("Email provider could not be reached");
      }
    }

    @Override
    public void send(SimpleMailMessage... messages) {
      for (SimpleMailMessage message : messages) send(message);
    }
  }
}
