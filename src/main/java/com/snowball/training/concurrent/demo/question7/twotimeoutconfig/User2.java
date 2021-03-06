package com.snowball.training.concurrent.demo.question7.twotimeoutconfig;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

import static javax.persistence.GenerationType.AUTO;

@Entity
@Data
public class User2 {
    @Id
    @GeneratedValue(strategy = AUTO)
    private Long id;
    private String name;
}
