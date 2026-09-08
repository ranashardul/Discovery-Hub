package com.stown.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
		assertThat(messageRepository).isNotNull();
	}

}
