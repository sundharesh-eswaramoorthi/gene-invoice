package com.geneinvoice.mail.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** A missing or bad required setting stops startup, naming the variable, without printing the secret. */
class MailPropertiesTest {

    private static final String API_KEY = "mail.api-key=an-api-key-of-20-chars";
    private static final String SECRETS_KEY = "mail.secrets-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Configuration
    @EnableConfigurationProperties(MailProperties.class)
    static class PropertiesOnly {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesOnly.class);

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) all.append(t.getMessage()).append('\n');
        return all.toString();
    }

    @Test
    void theKeysAreRequiredAndTheFailureNamesTheVariables() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(messages(context.getStartupFailure())).contains("MAIL_API_KEY").contains("MAIL_SECRETS_KEY");
        });
    }

    @Test
    void aShortApiKeyOrAKeyThatIsNot32BytesIsRefusedWithoutBeingPrinted() {
        runner.withPropertyValues("mail.api-key=too-short-key", "mail.secrets-key=c2hvcnQ=").run(context -> {
            assertThat(context).hasFailed();
            String messages = messages(context.getStartupFailure());
            assertThat(messages).contains("MAIL_API_KEY").contains("at least 16 characters").contains("MAIL_SECRETS_KEY");
            assertThat(messages).doesNotContain("too-short-key").doesNotContain("c2hvcnQ=");
        });
        runner.withPropertyValues(API_KEY, "mail.secrets-key=not base64!").run(context ->
                assertThat(messages(context.getStartupFailure())).contains("MAIL_SECRETS_KEY").doesNotContain("MAIL_API_KEY"));
    }

    @Test
    void aWebhookUrlNeedsASecret() {
        runner.withPropertyValues(API_KEY, SECRETS_KEY, "mail.webhook.url=http://localhost:8086/api/mail-service/events")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure())).contains("MAIL_WEBHOOK_SECRET");
                });
        runner.withPropertyValues(API_KEY, SECRETS_KEY, "mail.webhook.url=http://localhost:8086/api/mail-service/events",
                "mail.webhook.secret=a-webhook-secret-of-some-length").run(context -> assertThat(context).hasNotFailed());
        // Without a webhook, no secret is needed: events wait in the outbox.
        runner.withPropertyValues(API_KEY, SECRETS_KEY).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theDefaultsAreThoseOfTheDesign() {
        runner.withPropertyValues(API_KEY, SECRETS_KEY).run(context -> {
            assertThat(context).hasNotFailed();
            MailProperties p = context.getBean(MailProperties.class);
            assertThat(p.getQueue()).isEqualTo(MailProperties.Queue.RABBIT);
            assertThat(p.webhookEnabled()).isFalse();
            assertThat(p.getWebhook().getIntervalMs()).isEqualTo(1000);
            assertThat(p.getWebhook().getBatchSize()).isEqualTo(100);
            assertThat(p.getSend().getConcurrency()).isEqualTo(4);
            assertThat(p.getSend().getMaxConcurrency()).isEqualTo(8);
            assertThat(p.getSend().getPerMailboxIntervalMs()).isEqualTo(500);
            assertThat(p.getSend().getMaxAttempts()).isEqualTo(3);
            assertThat(p.getSend().getSweepIntervalMs()).isEqualTo(30000);
            assertThat(p.getSend().retryDelay(1)).isEqualTo(Duration.ofMinutes(1));
            assertThat(p.getSend().retryDelay(2)).isEqualTo(Duration.ofMinutes(5));
            assertThat(p.getTracking().getDeliveredAfter()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.getTracking().getConfirmWindow()).isEqualTo(Duration.ofHours(24));
            assertThat(p.getSync().isEnabled()).isTrue();
            assertThat(p.getSync().getIntervalMs()).isEqualTo(60000);
            assertThat(p.getGoogle().getApiBaseUrl()).isEqualTo("https://gmail.googleapis.com");
            assertThat(p.getGoogle().getTokenUrl()).isEqualTo("https://oauth2.googleapis.com/token");
            assertThat(p.getGoogle().getTokeninfoUrl()).isEqualTo("https://oauth2.googleapis.com/tokeninfo");
            assertThat(p.getGoogle().getRevokeUrl()).isEqualTo("https://oauth2.googleapis.com/revoke");
            assertThat(p.secretsKeyBytes()).hasSize(32);
        });
    }

    @Test
    void otherSettingsAreCheckedToo() {
        runner.withPropertyValues(API_KEY, SECRETS_KEY, "mail.queue=direct", "mail.send.concurrency=4",
                "mail.send.max-concurrency=2", "mail.sync.interval-ms=10", "mail.send.retry-delays=PT1S,PT2S,PT3S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure()))
                            .contains("mail.send.concurrency")
                            .contains("MAIL_SYNC_INTERVAL_MS")
                            .contains("PT1M, PT5M");
                });
        runner.withPropertyValues(API_KEY, SECRETS_KEY, "mail.queue=direct", "mail.send.retry-delays=PT1S,PT2S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MailProperties p = context.getBean(MailProperties.class);
                    assertThat(p.getQueue()).isEqualTo(MailProperties.Queue.DIRECT);
                    assertThat(p.getSend().retryDelay(1)).isEqualTo(Duration.ofSeconds(1));
                    assertThat(p.getSend().retryDelay(3)).isEqualTo(Duration.ofSeconds(2));
                });
    }
}
