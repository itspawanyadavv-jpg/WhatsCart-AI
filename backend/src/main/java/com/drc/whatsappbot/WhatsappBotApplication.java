package com.drc.whatsappbot;

import com.drc.whatsappbot.config.BotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BotProperties.class)
public class WhatsappBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(WhatsappBotApplication.class, args);
    }
}

