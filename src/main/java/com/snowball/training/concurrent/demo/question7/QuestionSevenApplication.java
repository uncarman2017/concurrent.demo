package com.snowball.training.concurrent.demo.question7;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

//@SpringBootApplication(exclude= {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@SpringBootApplication
@EnableJpaRepositories(basePackages = {"com.snowball.training.concurrent.demo"})
public class QuestionSevenApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuestionSevenApplication.class, args);
    }
}
