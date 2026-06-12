package net.fourletters.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(scanBasePackages="net.fourletters", exclude = {UserDetailsServiceAutoConfiguration.class})
public class FourLettersHub {

    public static void main(String[] args) {
        SpringApplication.run(FourLettersHub.class, args);
    }

}
