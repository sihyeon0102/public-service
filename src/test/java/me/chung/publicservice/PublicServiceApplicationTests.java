package me.chung.publicservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import me.chung.publicservice.seed.FacilitySeedRunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PublicServiceApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertTrue(context.getBeansOfType(FacilitySeedRunner.class).isEmpty());
    }

}
