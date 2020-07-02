package com.snowball.training.concurrent.demo.question4;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;


@Slf4j
public class Interesting {

    volatile int a = 1;
    volatile int b = 1;
    private static int COUNT = 1000000;

    public synchronized void add() {
        log.info("add start");
        for (int i = 0; i < COUNT; i++) {
            a++;
            b++;
        }
        log.info("add done");
    }

    public void compare() {
        log.info("compare start");
        for (int i = 0; i < COUNT; i++) {
            if (a < b) {
                log.info("a:{},b:{},{}", a, b, a > b);
            }
        }
        log.info("compare done");
    }

    public synchronized void compareRight() {
        log.info("compare start");
        for (int i = 0; i < COUNT; i++) {
            Assert.isTrue(a == b,"a==b");
            if (a < b) {
                log.info("a:{},b:{},{}", a, b, a > b);
            }

        }
        log.info("compare done");
    }
}
