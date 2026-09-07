package com.tamilvoice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// Keeps the blocking Groq SSE read loop off Tomcat's request threads.
@Configuration
public class StreamingExecutorConfig {

    @Bean
    public Executor chatStreamExecutor() {
        return Executors.newFixedThreadPool(64);
    }
}
