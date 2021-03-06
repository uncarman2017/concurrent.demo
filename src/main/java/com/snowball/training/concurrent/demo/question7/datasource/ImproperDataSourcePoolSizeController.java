package com.snowball.training.concurrent.demo.question7.datasource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("q7")
@Slf4j
public class ImproperDataSourcePoolSizeController {
    @Autowired
    private UserService userService;

    @GetMapping("testDS")
    public Object test() {
        return userService.register();
    }
}
