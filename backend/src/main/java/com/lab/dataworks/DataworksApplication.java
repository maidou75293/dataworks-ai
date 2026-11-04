package com.lab.dataworks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DataworksApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataworksApplication.class, args);
    }
}
