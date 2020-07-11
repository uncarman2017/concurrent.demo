package com.snowball.training.concurrent.demo.question8.feignandribbontimeout;

import com.snowball.training.concurrent.demo.common.Utils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CommonMistakesApplicationTimeoutByFeign {

    public static void main(String[] args) {
        Utils.loadPropertySource(FeignAndRibbonController.class, "default.properties");
        Utils.loadPropertySource(FeignAndRibbonController.class, "feign.properties");
        SpringApplication.run(CommonMistakesApplicationTimeoutByFeign.class, args);
    }
}

