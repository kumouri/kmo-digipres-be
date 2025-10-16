package com.kumouri.kmodigipresbe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class KmoDigipresBeApplicationTests {

    @Test
    void contextLoads() {
    }

}
