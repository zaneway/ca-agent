package com.github.zaneway.agent.ca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.github.zaneway.agent.ca.config.ModelConfig;

@SpringBootApplication
@EnableConfigurationProperties(ModelConfig.class)
public class CaApplication {

	public static void main(String[] args) {
		SpringApplication.run(CaApplication.class, args);
	}

}
