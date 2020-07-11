package com.snowball.training.concurrent.demo.question8.ribbonretry;

import com.snowball.training.concurrent.demo.common.Utils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class RibbonEntryApplication {

    public static void main(String[] args) {

        Utils.loadPropertySource(RibbonEntryApplication.class, "default-ribbon.properties");
        SpringApplication.run(RibbonEntryApplication.class, args);
    }
}

