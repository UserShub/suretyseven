package com.suretyseven.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class IdGenerator {
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    public String newApplicationId() {
        StringBuilder sb = new StringBuilder("APP-");
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
