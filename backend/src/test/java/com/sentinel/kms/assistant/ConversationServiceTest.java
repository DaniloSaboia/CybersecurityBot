package com.sentinel.kms.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationServiceTest {

    @Test
    void deriveTitleUsesFirst60CharsOnOneLine() {
        assertThat(ConversationService.deriveTitle("What is the playbook for   log4shell?"))
                .isEqualTo("What is the playbook for log4shell?");
        assertThat(ConversationService.deriveTitle(null)).isEqualTo("New conversation");
        assertThat(ConversationService.deriveTitle("   ")).isEqualTo("New conversation");

        String longQuestion = "a".repeat(100);
        String title = ConversationService.deriveTitle(longQuestion);
        assertThat(title).hasSize(58).endsWith("…");
    }
}
