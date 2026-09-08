package com.stown.casehold;

import com.stown.casehold.config.CaseHoldProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties(CaseHoldProperties.class)
@SpringBootApplication
public class CaseHoldServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseHoldServiceApplication.class, args);
    }
}
