package com.catchtable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CatchtableApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatchtableApplication.class, args);
    }
}
