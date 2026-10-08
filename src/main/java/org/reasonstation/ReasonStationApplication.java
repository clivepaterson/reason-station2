package org.reasonstation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
public class ReasonStationApplication {

    private static final Logger logger = LoggerFactory.getLogger(ReasonStationApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ReasonStationApplication.class, args);
        logger.info("Launch http://localhost:8080/index.html");
    }

}
