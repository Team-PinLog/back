package com.pinlog.pinlogback;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PinlogBackApplicationTests extends PostgresContainerSupport {

	@Test
	void contextLoads() {
	}

}
