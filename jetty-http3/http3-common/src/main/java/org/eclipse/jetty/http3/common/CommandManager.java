//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.common;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayDeque;
import java.util.Deque;

import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandManager
{
    private static final Logger LOG = LoggerFactory.getLogger(CommandManager.class);

    private final Deque<Command> commands = new ArrayDeque<>();
    private final ByteBufferPool bufferPool;

    public CommandManager(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    /**
     * @return false if the command was immediately processed, true if it was queued.
     */
    public boolean channelWrite(DatagramChannel channel, ByteBuffer buffer, SocketAddress peer) throws IOException
    {
        ChannelWriteCommand channelWriteCommand = new ChannelWriteCommand(buffer, channel, peer);
        if (!channelWriteCommand.execute())
        {
            commands.offer(channelWriteCommand);
            return true;
        }
        return false;
    }

    /**
     * @return false if the command was immediately processed, true if it was queued.
     */
    public boolean quicSend(QuicConnection connection, DatagramChannel channel) throws IOException
    {
        QuicSendCommand quicSendCommand = new QuicSendCommand(channel, connection);
        if (!quicSendCommand.execute())
        {
            commands.offer(quicSendCommand);
            return true;
        }
        return false;
    }

    /**
     * @return false if the command was immediately processed, true if it was queued.
     */
    public boolean quicTimeout(QuicConnection quicConnection, DatagramChannel channel, boolean dispose) throws IOException
    {
        QuicTimeoutCommand quicTimeoutCommand = new QuicTimeoutCommand(quicConnection, channel, dispose);
        if (!quicTimeoutCommand.execute())
        {
            commands.offer(quicTimeoutCommand);
            return true;
        }
        return false;
    }

    /**
     * @return true if at least one command is left in the queue, false if the queue was depleted.
     */
    public boolean processQueue() throws IOException
    {
        LOG.debug("processing commands : {}", commands);
        while (!commands.isEmpty())
        {
            Command command = commands.poll();
            LOG.debug("executing command {}", command);
            boolean completed = command.execute();
            LOG.debug("executed command; completed? {}", completed);
            if (!completed)
            {
                commands.offer(command);
                return true;
            }
        }
        return false;
    }

    private abstract static class Command
    {
        /**
         * @return true if the command completed, false if it needs to be re-executed.
         */
        public abstract boolean execute() throws IOException;

        @Override
        public String toString()
        {
            return getClass().getSimpleName();
        }
    }

    private class QuicTimeoutCommand extends Command
    {
        private final QuicSendCommand quicSendCommand;
        private final boolean dispose;
        private boolean timeoutCalled;

        public QuicTimeoutCommand(QuicConnection quicConnection, DatagramChannel channel, boolean dispose)
        {
            this.dispose = dispose;
            this.quicSendCommand = new QuicSendCommand("timeout", channel, quicConnection);
        }

        @Override
        public boolean execute() throws IOException
        {
            if (!timeoutCalled)
            {
                LOG.debug("notifying quiche of timeout");
                quicSendCommand.quicConnection.quicOnTimeout();
                timeoutCalled = true;
            }
            boolean written = quicSendCommand.execute();
            if (!written)
                return false;
            if (dispose)
            {
                LOG.debug("disposing of quiche connection");
                quicSendCommand.quicConnection.quicDispose();
            }
            return true;
        }
    }

    private class QuicSendCommand extends Command
    {
        private final String cmdName;
        private final DatagramChannel channel;
        private final QuicConnection quicConnection;

        private ByteBuffer buffer;

        public QuicSendCommand(DatagramChannel channel, QuicConnection quicConnection)
        {
            this("send", channel, quicConnection);
        }

        private QuicSendCommand(String cmdName, DatagramChannel channel, QuicConnection quicConnection)
        {
            this.cmdName = cmdName;
            this.channel = channel;
            this.quicConnection = quicConnection;
        }

        @Override
        public boolean execute() throws IOException
        {
            LOG.debug("executing {} command", cmdName);
            if (buffer != null)
            {
                int channelSent = channel.send(buffer, quicConnection.getRemoteAddress());
                LOG.debug("resuming sending to channel made it send {} bytes", channelSent);
                if (channelSent == 0)
                {
                    LOG.debug("executed {} command; channel sending(1) could not be done", cmdName);
                    return false;
                }
                buffer.clear();
            }
            else
            {
                LOG.debug("fresh command execution");
                buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
                BufferUtil.flipToFill(buffer);
            }

            while (true)
            {
                int quicSent = quicConnection.quicSend(buffer);
                if (quicSent == 0)
                {
                    LOG.debug("executed {} command; all done", cmdName);
                    bufferPool.release(buffer);
                    buffer = null;
                    return true;
                }
                LOG.debug("quiche wants to send {} byte(s)", quicSent);
                buffer.flip();
                int channelSent = channel.send(buffer, quicConnection.getRemoteAddress());
                LOG.debug("channel sent {} byte(s)", channelSent);
                if (channelSent == 0)
                {
                    LOG.debug("executed {} command; channel sending(2) could not be done", cmdName);
                    return false;
                }
                buffer.clear();
            }
        }
    }

    private class ChannelWriteCommand extends Command
    {
        private final ByteBuffer buffer;
        private final DatagramChannel channel;
        private final SocketAddress peer;

        private ChannelWriteCommand(ByteBuffer buffer, DatagramChannel channel, SocketAddress peer)
        {
            this.buffer = buffer;
            this.channel = channel;
            this.peer = peer;
        }

        @Override
        public boolean execute() throws IOException
        {
            LOG.debug("executing channel write command");
            int sent = channel.send(buffer, peer);
            if (sent == 0)
            {
                LOG.debug("executed channel write command; channel sending could not be done");
                return false;
            }
            LOG.debug("channel sent {} byte(s)", sent);
            bufferPool.release(buffer);
            LOG.debug("executed channel write command; all done");
            return true;
        }
    }
}