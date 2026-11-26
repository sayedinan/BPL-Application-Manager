package com.bpl.orderapp.admin.ssh;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Remote error output may hold secrets, and JSch closes any stream it is given. */
class SshStderrTest {

    @Test
    void remoteErrorOutputIsNotPipedIntoTheBackendsOwnStreams() throws Exception {
        Session session = mock(Session.class);
        ChannelExec channel = mock(ChannelExec.class);
        when(session.openChannel("exec")).thenReturn(channel);
        when(channel.getInputStream()).thenReturn(InputStream.nullInputStream());
        when(channel.isClosed()).thenReturn(true);

        new SshConnection().runScript(session, "true");

        verify(channel, never()).setErrStream(any());
        verify(channel, never()).setErrStream(any(), anyBoolean());
    }
}
