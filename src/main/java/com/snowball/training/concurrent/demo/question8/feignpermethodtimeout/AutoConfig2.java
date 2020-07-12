package com.snowball.training.concurrent.demo.question8.feignpermethodtimeout;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;


@Configuration
@EnableFeignClients(
        basePackages = {
                "com.snowball.training.concurrent.demo.question8.feignpermethodtimeout",
        }
)
public class AutoConfig2 {
}
