package com.geneinvoice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GeneInvoiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeneInvoiceApplication.class, args);
    }
}
