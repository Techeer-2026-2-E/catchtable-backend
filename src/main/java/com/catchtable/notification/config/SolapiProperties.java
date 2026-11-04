package com.catchtable.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "alimtalk.solapi")
public class SolapiProperties {
    private String baseUrl = "https://api.solapi.com";
    private String apiKey = "";
    private String apiSecret = "";
    private String channelId = "";
    private String templateId = "";
    private String webhookSecret = "";
}
