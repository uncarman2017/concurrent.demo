package com.snowball.training.concurrent.demo.question8.ribbonretry;

import com.snowball.training.concurrent.demo.common.Utils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class RibbonEntryApplicationNoRetry {

    public static void main(String[] args) {

        Utils.loadPropertySource(RibbonEntryApplicationNoRetry.class, "noretry-ribbon.properties");
        SpringApplication.run(RibbonEntryApplicationNoRetry.class, args);
    }
}

