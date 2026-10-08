package com.autotrade.app.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 시뮬레이션 실험실 프로세스 진입점(D-013의 ③). 실전 주문 경로는 이 프로세스에 두지 않는다. */
@SpringBootApplication
public class AutotradeLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutotradeLabApplication.class, args);
    }
}
