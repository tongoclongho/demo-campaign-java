package com.demo.campaign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DemoCampaignJavaApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoCampaignJavaApplication.class, args);
	}

}
