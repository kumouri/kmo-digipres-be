package com.kumouri.kmodigipresbe;

import org.springframework.boot.SpringApplication;

public class TestKmoDigipresBeApplication {

    public static void main(String[] args) {
        SpringApplication.from(KmoDigipresBeApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
