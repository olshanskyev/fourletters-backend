package net.fourletters.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages="net.fourletters", exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
public class FourLettersHub {

    public static void main(String[] args) {
        SpringApplication.run(FourLettersHub.class, args);
    }

}
