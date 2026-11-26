package com.bpl.orderapp.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.bpl.orderapp.admin.auth.dto.LoginRequest;
import com.bpl.orderapp.admin.user.dto.CreateUserRequest;
import com.bpl.orderapp.admin.user.dto.UpdateUserRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Usernames end up in log lines, so they must not be able to start a new one. */
class LogInjectionTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private List<Object> requests(String username) {
        return List.of(
            new LoginRequest(username, "password"),
            new CreateUserRequest(username, "Full Name", "USER", "a@example.com", "8801712345678", null),
            new UpdateUserRequest(username, null, null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\tb", "a\u0000b", "a\u001bb", "a\u0085b", "a b", "a b"})
    void controlCharactersAndLineBreaksAreRejected(String username) {
        for (Object request : requests(username)) {
            assertThat(validator.validate(request)).as(request.getClass().getSimpleName())
                .anyMatch(v -> v.getPropertyPath().toString().equals("username"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "j.doe@bpl", "José O'Brien", "user_1-x"})
    void ordinaryUsernamesStillWork(String username) {
        for (Object request : requests(username)) {
            assertThat(validator.validate(request)).as(request.getClass().getSimpleName()).isEmpty();
        }
    }

    /** A hand-built JSON pattern does not escape the message; Spring's structured format does. */
    @Test
    void productionLogsUseARealJsonEncoder() throws IOException {
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"));

        assertThat(prod).contains("structured:").contains("console: logstash").doesNotContain("\"msg\":\"%msg\"");
    }
}
