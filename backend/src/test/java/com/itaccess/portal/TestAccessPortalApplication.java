package com.itaccess.portal;

import org.springframework.boot.SpringApplication;

public class TestAccessPortalApplication {

	public static void main(String[] args) {
		SpringApplication.from(AccessPortalApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
