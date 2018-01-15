/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.transport.impl.actor;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;

import io.zeebe.dispatcher.BlockPeek;
import io.zeebe.dispatcher.Subscription;
import io.zeebe.transport.NotConnectedException;
import io.zeebe.transport.Loggers;
import io.zeebe.transport.impl.ControlMessages;
import io.zeebe.transport.impl.SendFailureHandler;
import io.zeebe.transport.impl.TransportChannel;
import io.zeebe.transport.impl.TransportContext;
import io.zeebe.util.actor.Actor;
import io.zeebe.util.state.ComposedState;
import io.zeebe.util.state.SimpleStateMachineContext;
import io.zeebe.util.state.State;
import io.zeebe.util.state.StateMachine;
import io.zeebe.util.state.StateMachineAgent;
import io.zeebe.util.time.ClockUtil;

public class Sender implements Actor
{
    private final Int2ObjectHashMap<TransportChannel> channelMap = new Int2ObjectHashMap<>();
    private final Subscription senderSubscription;
    private final int maxPeekSize;
    protected final long keepAlivePeriod;
    protected final SendFailureHandler sendFailureHandler;

    protected long lastKeepAlive = 0;

    private static final int DEFAULT = 0;
    private static final int DISCARD = 1;
    private static final int SEND_NEXT_KEEP_ALIVE = 2;

    private final PollState pollState = new PollState();
    private final ProcessState processState = new ProcessState();
    private final DiscardState discardState = new DiscardState();
    private final SendKeepAliveState keepAliveState = new SendKeepAliveState();

    private final StateMachine<SenderContext> stateMachine = StateMachine.<SenderContext>builder((s) -> new SenderContext(s))
        .initialState(pollState)
        .from(pollState).take(DEFAULT).to(processState)
        .from(pollState).take(SEND_NEXT_KEEP_ALIVE).to(keepAliveState)
        .from(keepAliveState).take(SEND_NEXT_KEEP_ALIVE).to(keepAliveState)
        .from(keepAliveState).take(DEFAULT).to(pollState)
        .from(processState).take(DISCARD).to(discardState)
        .from(processState).take(DEFAULT).to(pollState)
        .from(discardState).take(DEFAULT).to(pollState)
        .build();

    private StateMachineAgent<SenderContext> stateMachineAgent = new StateMachineAgent<>(stateMachine);

    public Sender(ActorContext actorContext, TransportContext context)
    {
        this.senderSubscription = context.getSenderSubscription();
        this.maxPeekSize = context.getMessageMaxLength() * 16;
        this.sendFailureHandler = context.getSendFailureHandler();
        this.keepAlivePeriod = context.getChannelKeepAlivePeriod();

        actorContext.setSender(this);
    }

    @Override
    public int doWork() throws Exception
    {
        return stateMachineAgent.doWork();
    }

    class PollState implements State<SenderContext>
    {
        @Override
        public int doWork(SenderContext context)
        {
            context.reset();

            final long now = ClockUtil.getCurrentTimeInMillis();
            if (keepAlivePeriod > 0 && now - lastKeepAlive > keepAlivePeriod && !channelMap.isEmpty())
            {
                context.take(SEND_NEXT_KEEP_ALIVE);
                lastKeepAlive = now;
                return 1;
            }
            else
            {
                final int blockSize = senderSubscription.peekBlock(context.blockPeek, maxPeekSize, true);

                if (blockSize > 0)
                {
                    context.take(DEFAULT);
                }

                return blockSize;
            }
        }
    }

    class ProcessState extends ComposedState<SenderContext>
    {
        private final AwaitChannelState awaitChannelState = new AwaitChannelState();
        private final WriteState writeState = new WriteState();

        class AwaitChannelState implements Step<SenderContext>
        {
            @Override
            public boolean doWork(SenderContext context)
            {
                final BlockPeek blockPeek = context.blockPeek;
                final TransportChannel ch = channelMap.get(blockPeek.getStreamId());

                if (ch != null && !ch.isClosed())
                {
                    context.writeChannel = ch;
                    return true;
                }
                else
                {
                    context.failure = "No available channel for remote";
                    context.failureCause = new NotConnectedException(context.failure);
                    context.take(DISCARD);
                    return false;
                }

            }
        }

        class WriteState implements Step<SenderContext>
        {
            @Override
            public boolean doWork(SenderContext context)
            {
                final BlockPeek blockPeek = context.blockPeek;
                final TransportChannel writeChannel = context.writeChannel;

                final int bytesWritten = writeChannel.write(blockPeek.getRawBuffer());

                if (bytesWritten == -1)
                {
                    context.failure = "Could not write to channel";
                    context.take(DISCARD);
                    return false;
                }
                else
                {
                    context.bytesWritten += bytesWritten;

                    if (context.bytesWritten == blockPeek.getBlockLength())
                    {
                        blockPeek.markCompleted();
                        context.take(DEFAULT);
                        return true;
                    }
                    else
                    {
                        return false;
                    }
                }
            }
        }

        @Override
        protected List<Step<SenderContext>> steps()
        {
            return Arrays.asList(awaitChannelState, writeState);
        }

    }

    class DiscardState implements State<SenderContext>
    {

        @Override
        public int doWork(SenderContext context) throws Exception
        {
            final BlockPeek blockPeek = context.blockPeek;

            if (sendFailureHandler != null)
            {
                final Iterator<DirectBuffer> messagesIt = blockPeek.iterator();
                while (messagesIt.hasNext())
                {

                    final DirectBuffer nextMessage = messagesIt.next();
                    sendFailureHandler.onFragment(
                            nextMessage,
                            0,
                            nextMessage.capacity(),
                            blockPeek.getStreamId(),
                            context.failure,
                            context.failureCause);
                }
            }

            blockPeek.markFailed();

            context.take(DEFAULT);

            return 1;
        }
    }


    class SendKeepAliveState extends ComposedState<SenderContext>
    {

        protected final SelectChannelStep selectStep = new SelectChannelStep();
        protected final SendKeepAliveOnChannelStep sendStep = new SendKeepAliveOnChannelStep();

        @Override
        protected List<Step<SenderContext>> steps()
        {
            return Arrays.asList(selectStep, sendStep);
        }

        class SelectChannelStep implements Step<SenderContext>
        {
            @Override
            public boolean doWork(SenderContext context)
            {
                if (context.channelIt == null)
                {
                    context.channelIt = channelMap.values().iterator();
                }

                if (context.channelIt.hasNext())
                {
                    context.writeChannel = context.channelIt.next();
                }
                else
                {
                    context.writeChannel = null;
                }

                context.keepAliveBuffer.clear();

                return true;
            }
        }

        class SendKeepAliveOnChannelStep implements Step<SenderContext>
        {
            @Override
            public boolean doWork(SenderContext context)
            {
                if (context.writeChannel != null)
                {
                    boolean continueWithNextChannel = false;

                    if (context.keepAliveBuffer.remaining() > 0)
                    {
                        final int bytesSent = context.writeChannel.write(context.keepAliveBuffer);

                        if (bytesSent < 0)
                        {
                            // Just ignore the channel on failure.
                            // No need to do anything else, as the channel will be closed
                            // by other means.
                            continueWithNextChannel = true;
                        }
                    }

                    if (context.keepAliveBuffer.remaining() == 0)
                    {
                        continueWithNextChannel = true;
                    }

                    if (continueWithNextChannel)
                    {
                        context.take(SEND_NEXT_KEEP_ALIVE);
                    }

                    return continueWithNextChannel;
                }
                else
                {
                    context.take(DEFAULT);
                    return true;
                }
            }
        }

        @Override
        public boolean isInterruptable()
        {
            // avoids getting new channels registered or removed while iterating them
            return false;
        }
    }

    public void removeChannel(TransportChannel c)
    {
        stateMachineAgent.addCommand((ctx) ->
        {
            channelMap.remove(c.getStreamId());
        });
    }

    public void registerChannel(TransportChannel c)
    {
        // record the time before submitting the command because this is closer to the point in time the
        // channel was opened (=> and makes the behavior more predictable in test)
        final long now = ClockUtil.getCurrentTimeInMillis();

        stateMachineAgent.addCommand((ctx) ->
        {
            if (channelMap.isEmpty())
            {
                lastKeepAlive = now;
            }

            channelMap.put(c.getStreamId(), c);
            Loggers.TRANSPORT_LOGGER.debug("Channel opened to remote {}", c.getRemoteAddress());
        });
    }

    static class SenderContext extends SimpleStateMachineContext
    {
        final BlockPeek blockPeek = new BlockPeek();

        TransportChannel writeChannel;
        int bytesWritten;

        String failure;
        Exception failureCause;

        Iterator<TransportChannel> channelIt;
        final ByteBuffer keepAliveBuffer = ByteBuffer.allocate(ControlMessages.KEEP_ALIVE.capacity());

        SenderContext(StateMachine<?> stateMachine)
        {
            super(stateMachine);
            ControlMessages.KEEP_ALIVE.getBytes(0, keepAliveBuffer, ControlMessages.KEEP_ALIVE.capacity());
            keepAliveBuffer.flip();
        }

        @Override
        public void reset()
        {
            writeChannel = null;
            bytesWritten = 0;
            channelIt = null;
            keepAliveBuffer.clear();
            failure = null;
            failureCause = null;
        }
    }
}
