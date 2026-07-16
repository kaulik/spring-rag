package com.example.rag;

import com.example.rag.memory.redis.RedisCheckpointSaver;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ApplicationTests {

    @Autowired
    private RedisCheckpointSaver checkpointSaver;

    @Test
    void contextLoads() {
        // Verifies that the Spring context assembles without errors.
    }

    @Test
    void redisCheckpointSaverBeanIsUsableNotJustConstructible() {
        // Regression test for a real production NPE: RedisCheckpointSaver was
        // @Repository, which triggers Spring's exception-translation CGLIB
        // proxying — via Objenesis, meaning NO constructor in the chain runs
        // on the proxy instance, leaving AbstractCheckpointSaver's
        // `private final ReentrantLock _lock = new ReentrantLock()` null
        // forever. RedisCheckpointSaverTest didn't catch this because it
        // constructs the class directly with `new`, bypassing Spring
        // entirely — this exercises the actual Spring-managed bean instead.
        assertDoesNotThrow(() ->
                checkpointSaver.get(RunnableConfig.builder().threadId("contextLoads-smoke-test").build()));
    }
}
