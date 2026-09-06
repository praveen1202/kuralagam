package com.tamilvoice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Dedicated bounded thread pool for the blocking Groq SSE read loop, so
 * streaming chat responses don't tie up Tomcat's request-handling threads
 * for the duration of the stream.
 */
@Configuration
public class StreamingExecutorConfig {

    @Bean
    public Executor chatStreamExecutor() {
        return Executors.newFixedThreadPool(64);
    }
}
