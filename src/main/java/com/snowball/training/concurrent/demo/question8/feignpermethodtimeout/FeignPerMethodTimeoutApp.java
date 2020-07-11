package com.snowball.training.concurrent.demo.question8.feignpermethodtimeout;


import com.snowball.training.concurrent.demo.common.Utils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class FeignPerMethodTimeoutApp {

    public static void main(String[] args) {
        Utils.loadPropertySource(FeignPerMethodTimeoutApp.class, "default.properties");
        SpringApplication.run(FeignPerMethodTimeoutApp.class, args);
    }
}

