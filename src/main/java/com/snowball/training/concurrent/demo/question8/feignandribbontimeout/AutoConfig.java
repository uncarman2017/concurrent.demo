package com.snowball.training.concurrent.demo.question8.feignandribbontimeout;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableFeignClients(basePackages = "com.snowball.training.concurrent.demo.question8.feignandribbontimeout")
public class AutoConfig {
}
