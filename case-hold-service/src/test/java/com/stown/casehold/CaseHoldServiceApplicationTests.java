package com.stown.casehold;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaseHoldServiceApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
        assertThat(outboxEventRepository).isNotNull();
    }
}
