package br.com.sport.accesscontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AccessControlApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessControlApiApplication.class, args);
    }
}
