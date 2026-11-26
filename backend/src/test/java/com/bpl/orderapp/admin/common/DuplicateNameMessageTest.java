package com.bpl.orderapp.admin.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The same exception covers users and applications, so the message must say which. */
class DuplicateNameMessageTest {

    @Test
    void anApplicationClashIsNotReportedAsAUsername() {
        assertThat(DuplicateNameException.application("Orders").getMessage())
            .isEqualTo("An application with this name already exists: Orders");
        assertThat(new DuplicateNameException("bot").getMessage()).isEqualTo("Username already exists: bot");
    }
}
