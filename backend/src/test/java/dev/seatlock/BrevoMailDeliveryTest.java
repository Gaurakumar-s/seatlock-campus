package dev.seatlock;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class BrevoMailDeliveryTest {
  private SimpleMailMessage message() {
    var message = new SimpleMailMessage();
    message.setFrom("sender@example.test");
    message.setTo("student@example.test");
    message.setSubject("Your SeatLock Campus verification code");
    message.setText("Your verification code is 123456. It expires in 10 minutes.");
    return message;
  }

  @Test
  void postsVerificationToHttpsProviderContract() throws Exception {
    var json = new ObjectMapper();
    var body = new AtomicReference<JsonNode>();
    var header = new AtomicReference<String>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v3/smtp/email",
        exchange -> {
          header.set(exchange.getRequestHeaders().getFirst("api-key"));
          body.set(json.readTree(exchange.getRequestBody()));
          exchange.sendResponseHeaders(201, -1);
          exchange.close();
        });
    server.start();
    try {
      var sender =
          new BrevoMailConfiguration.BrevoSender(
              json,
              "test-key",
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email"),
              HttpClient.newHttpClient());
      sender.send(message());
      assertEquals("test-key", header.get());
      assertEquals("sender@example.test", body.get().at("/sender/email").asText());
      assertEquals("student@example.test", body.get().at("/to/0/email").asText());
      assertEquals(1, body.get().get("to").size());
      assertTrue(body.get().get("textContent").asText().contains("123456"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void providerFailureDoesNotLeakResponseOrPretendSuccess() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/send",
        exchange -> {
          byte[] detail = "private-provider-response".getBytes();
          exchange.sendResponseHeaders(401, detail.length);
          exchange.getResponseBody().write(detail);
          exchange.close();
        });
    server.start();
    try {
      var sender =
          new BrevoMailConfiguration.BrevoSender(
              new ObjectMapper(),
              "invalid-key",
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/send"),
              HttpClient.newHttpClient());
      var error = assertThrows(MailSendException.class, () -> sender.send(message()));
      assertFalse(error.getMessage().contains("private-provider-response"));
      assertFalse(error.getMessage().contains("invalid-key"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void missingKeyPreventsBrevoStartup() {
    assertThrows(
        IllegalStateException.class,
        () -> new BrevoMailConfiguration().brevoMailSender(new ObjectMapper(), ""));
  }

  @Test
  void springUsesHttpsSenderInsteadOfSmtpWhenConfigured() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
        .withUserConfiguration(BrevoMailConfiguration.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues(
            "spring.mail.host=localhost",
            "seatlock.mail-transport=brevo",
            "seatlock.brevo-api-key=test-key")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(1, context.getBeansOfType(JavaMailSender.class).size());
              assertInstanceOf(
                  BrevoMailConfiguration.BrevoSender.class, context.getBean(JavaMailSender.class));
            });
  }
}
