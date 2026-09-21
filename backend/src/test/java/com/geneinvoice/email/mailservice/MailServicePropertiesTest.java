package com.geneinvoice.email.mailservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.email.transport.MailConnections;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.email.transport.NoopMailTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MailServicePropertiesTest {

    private final ApplicationContextRunner transports = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(MailServiceProperties.class, MailServiceClient.class, NoopMailTransport.class);

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) all.append(t.getMessage()).append('\n');
        return all.toString();
    }

    @Test
    void startupFailsNamingWhatIsMissing() {
        transports.withPropertyValues("app.mail.transport=mail-service").run(context -> {
            assertThat(context).hasFailed();
            assertThat(messages(context.getStartupFailure()))
                    .contains("app.mail.service")
                    .contains("MAIL_SERVICE_API_KEY")
                    .contains("MAIL_SERVICE_WEBHOOK_SECRET");
        });
        transports.withPropertyValues("app.mail.transport=mail-service", "app.mail.service.api-key=key-0123456789abcdef",
                        "app.mail.service.webhook-secret= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure()))
                            .contains("MAIL_SERVICE_WEBHOOK_SECRET")
                            .doesNotContain("key-0123456789abcdef");
                });
    }

    @Test
    void withTheMailServiceSetUpItIsTheTransportAndKeepsTheConnections() {
        transports.withPropertyValues("app.mail.transport=mail-service",
                        "app.mail.service.api-key=key-0123456789abcdef",
                        "app.mail.service.webhook-secret=secret-0123456789abcdef")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(MailTransport.class).isInstanceOf(MailServiceClient.class);
                    assertThat(context).getBean(MailConnections.class).isInstanceOf(MailServiceClient.class);
                    assertThat(context.getBean(MailTransport.class).isConfigured()).isTrue();
                    MailServiceProperties properties = context.getBean(MailServiceProperties.class);
                    assertThat(properties.getUrl()).isEqualTo("http://localhost:8091");
                    assertThat(properties.getConnectTimeoutMs()).isEqualTo(5000);
                    assertThat(properties.getReadTimeoutMs()).isEqualTo(40000);
                });
    }

    @Test
    void withoutItNothingIsCheckedAndEmailIsSavedButNotSent() {
        transports.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MailServiceProperties.class);
            assertThat(context).getBean(MailTransport.class).isInstanceOf(NoopMailTransport.class);
            assertThat(context.getBean(MailTransport.class).isConfigured()).isFalse();
        });
        transports.withPropertyValues("app.mail.transport=none").run(context ->
                assertThat(context).getBean(MailConnections.class).isInstanceOf(NoopMailTransport.class));
    }
}
