package net.fourletters.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages="net.fourletters")
public class FourLettersHub {

    public static void main(String[] args) {
        SpringApplication.run(FourLettersHub.class, args);
    }

}
