package com.geneinvoice.mail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sends Gene Invoice email through each internal user's own Gmail, one copy per recipient, from a
 * RabbitMQ queue, and tells the backend what became of every copy through a signed webhook.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MailServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailServiceApplication.class, args);
    }
}
