package com.bpl.orderapp.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserRepository;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The first Sys.Admin password goes to a private one-time file, never to the logs. */
@ExtendWith(OutputCaptureExtension.class)
class SysAdminSeederTest {

    @TempDir
    Path dir;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserRepository users = mock(UserRepository.class);

    private SysAdminSeeder seeder(Path file) {
        return new SysAdminSeeder(encoder, "admin", file.toString(), users);
    }

    @Test
    void thePasswordIsWrittenToTheFileAndNotLogged(CapturedOutput output) throws Exception {
        Path file = dir.resolve("new").resolve("initial-password.txt");

        seeder(file).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        String password = Files.readString(file).strip();
        assertThat(password).hasSizeGreaterThanOrEqualTo(32);
        assertThat(encoder.matches(password, saved.getValue().getPasswordHash())).isTrue();
        assertThat(saved.getValue().isMustChangePassword()).isTrue();
        assertThat(output.getAll()).doesNotContain(password).contains(file.toString());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void ifTheFileCannotBeWrittenNoAccountIsCreated() throws IOException {
        Path blocker = Files.writeString(dir.resolve("not-a-directory"), "x");

        assertThatThrownBy(() -> seeder(blocker.resolve("pw.txt")).run(null)).isInstanceOf(IOException.class);

        verify(users, never()).save(any());
    }

    private Path fileWithSysAdmin(String password, boolean mustChangePassword) throws IOException {
        Path file = Files.writeString(dir.resolve("pw.txt"), "initial-secret" + System.lineSeparator());
        User admin = new User();
        admin.setUsername("whoever");
        admin.setPasswordHash(encoder.encode(password));
        admin.setMustChangePassword(mustChangePassword);
        when(users.countByRole("SYS_ADMIN")).thenReturn(1L);
        when(users.findByRole("SYS_ADMIN")).thenReturn(java.util.List.of(admin));
        return file;
    }

    @Test
    void theFileIsRemovedOnceThePasswordHasBeenChanged() throws Exception {
        Path file = fileWithSysAdmin("a-new-password", false);

        seeder(file).run(null);

        assertThat(file).doesNotExist();
    }

    @Test
    void theFileIsKeptWhileItsPasswordStillWorks() throws Exception {
        Path file = fileWithSysAdmin("initial-secret", true);

        seeder(file).run(null);

        assertThat(file).exists();
    }

    @Test
    void withNoSysAdminLeftAndTheNameTakenARecoveryAccountIsCreated(CapturedOutput output) throws Exception {
        when(users.existsByUsernameIgnoreCase("admin")).thenReturn(true);
        when(users.existsByUsernameIgnoreCase("admin-recovery")).thenReturn(true);

        seeder(dir.resolve("pw.txt")).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin-recovery-2");
        assertThat(saved.getValue().getRole()).isEqualTo("SYS_ADMIN");
        assertThat(output.getAll()).contains("admin-recovery-2");
    }
}
