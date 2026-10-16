package com.familyapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FamilyAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyAppApplication.class, args);
    }
}
