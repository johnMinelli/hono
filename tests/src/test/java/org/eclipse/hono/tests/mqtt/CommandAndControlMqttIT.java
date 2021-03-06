/*******************************************************************************
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.tests.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.jms.IllegalStateException;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.MessageSender;
import org.eclipse.hono.service.management.tenant.Tenant;
import org.eclipse.hono.tests.CommandEndpointConfiguration.SubscriberRole;
import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.util.EventConstants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.ResourceIdentifier;
import org.eclipse.hono.util.TimeUntilDisconnectNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.proton.ProtonHelper;


/**
 * Integration tests for sending commands to device connected to the MQTT adapter.
 *
 */
@ExtendWith(VertxExtension.class)
public class CommandAndControlMqttIT extends MqttTestBase {

    private static final int COMMANDS_TO_SEND = 60;

    private String tenantId;
    private String deviceId;
    private final String password = "secret";
    private Tenant tenant;

    static Stream<MqttCommandEndpointConfiguration> allCombinations() {
        return Stream.of(
                new MqttCommandEndpointConfiguration(SubscriberRole.DEVICE, false),
                new MqttCommandEndpointConfiguration(SubscriberRole.GATEWAY_FOR_ALL_DEVICES, false),
                new MqttCommandEndpointConfiguration(SubscriberRole.GATEWAY_FOR_SINGLE_DEVICE, false),

                // the following variants can be removed once we no longer support the legacy topic filters
                new MqttCommandEndpointConfiguration(SubscriberRole.DEVICE, true),
                new MqttCommandEndpointConfiguration(SubscriberRole.GATEWAY_FOR_ALL_DEVICES, true),
                new MqttCommandEndpointConfiguration(SubscriberRole.GATEWAY_FOR_SINGLE_DEVICE, true)
                );
    }

    /**
     * Sets up the fixture.
     */
    @BeforeEach
    @Override
    public void setUp(final TestInfo testInfo) {
        LOGGER.info("running {}", testInfo.getDisplayName());
        tenantId = helper.getRandomTenantId();
        deviceId = helper.getRandomDeviceId(tenantId);
        tenant = new Tenant();
    }

    private Future<MessageConsumer> createConsumer(final String tenantId, final Consumer<Message> messageConsumer) {

        return helper.applicationClientFactory.createEventConsumer(tenantId, messageConsumer, remoteClose -> {});
    }

    private Future<Void> subscribeToCommands(
            final String commandTargetDeviceId,
            final Handler<MqttPublishMessage> msgHandler,
            final MqttCommandEndpointConfiguration endpointConfig,
            final MqttQoS qos) {

        final Promise<Void> result = Promise.promise();
        context.runOnContext(go -> {
            mqttClient.publishHandler(msgHandler);
            mqttClient.subscribeCompletionHandler(subAckMsg -> {
                if (subAckMsg.grantedQoSLevels().contains(qos.value())) {
                    result.complete();
                } else {
                    result.fail("could not subscribe to command topic");
                }
            });
            mqttClient.subscribe(endpointConfig.getCommandTopicFilter(commandTargetDeviceId), qos.value());
        });
        return result.future();
    }

    /**
     * Verifies that the adapter forwards on-way commands from
     * an application to a device.
     * 
     * @param endpointConfig The endpoints to use for sending/receiving commands.
     * @param ctx The vert.x test context.
     * @throws InterruptedException if not all commands and responses are exchanged in time.
     */
    @ParameterizedTest(name = IntegrationTestSupport.PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("allCombinations")
    @Timeout(timeUnit = TimeUnit.SECONDS, value = 10)
    public void testSendOneWayCommandSucceeds(
            final MqttCommandEndpointConfiguration endpointConfig,
            final VertxTestContext ctx) throws InterruptedException {

        final String commandTargetDeviceId = endpointConfig.isSubscribeAsGateway()
                ? helper.setupGatewayDeviceBlocking(tenantId, deviceId, 5)
                : deviceId;
        final Checkpoint commandsReceived = ctx.checkpoint(COMMANDS_TO_SEND);

        final AtomicInteger counter = new AtomicInteger();
        testSendCommandSucceeds(ctx, commandTargetDeviceId, msg -> {
            LOGGER.trace("received one-way command [topic: {}]", msg.topicName());
            final ResourceIdentifier topic = ResourceIdentifier.fromString(msg.topicName());
            ctx.verify(() -> {
                endpointConfig.assertCommandPublishTopicStructure(topic, commandTargetDeviceId, true, "setValue");
            });
            commandsReceived.flag();
        }, payload -> {
            return helper.sendOneWayCommand(
                    tenantId,
                    commandTargetDeviceId,
                    "setValue",
                    "text/plain",
                    payload,
                    // set "forceCommandRerouting" message property so that half the command are rerouted via the AMQP network
                    IntegrationTestSupport.newCommandMessageProperties(() -> counter.getAndIncrement() >= COMMANDS_TO_SEND/2),
                    200);
        }, endpointConfig, COMMANDS_TO_SEND, MqttQoS.AT_MOST_ONCE);
    }

    /**
     * Verifies that the adapter forwards commands with Qos 0 and response hence and forth between an application and a
     * device.
     *
     * @param endpointConfig The endpoints to use for sending/receiving commands.
     * @param ctx The vert.x test context.
     * @throws InterruptedException if not all commands and responses are exchanged in time.
     */
    @ParameterizedTest(name = IntegrationTestSupport.PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("allCombinations")
    public void testSendCommandSucceedsWithQos0(
            final MqttCommandEndpointConfiguration endpointConfig,
            final VertxTestContext ctx) throws InterruptedException {

        testSendCommandSucceeds(ctx, endpointConfig, MqttQoS.AT_MOST_ONCE);
    }

    /**
     * Verifies that the adapter forwards commands with Qos 1 and response hence and forth between an application and a
     * device.
     *
     * @param endpointConfig The endpoints to use for sending/receiving commands.
     * @param ctx The vert.x test context.
     * @throws InterruptedException if not all commands and responses are exchanged in time.
     */
    @ParameterizedTest(name = IntegrationTestSupport.PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("allCombinations")
    public void testSendCommandSucceedsWithQos1(
            final MqttCommandEndpointConfiguration endpointConfig,
            final VertxTestContext ctx) throws InterruptedException {

        testSendCommandSucceeds(ctx, endpointConfig, MqttQoS.AT_LEAST_ONCE);
    }

    private void testSendCommandSucceeds(
            final VertxTestContext ctx,
            final MqttCommandEndpointConfiguration endpointConfig,
            final MqttQoS qos) throws InterruptedException {

        final String commandTargetDeviceId = endpointConfig.isSubscribeAsGateway()
                ? helper.setupGatewayDeviceBlocking(tenantId, deviceId, 5)
                : deviceId;

        final AtomicInteger counter = new AtomicInteger();
        testSendCommandSucceeds(ctx, commandTargetDeviceId, msg -> {
            LOGGER.trace("received command [{}]", msg.topicName());
            final ResourceIdentifier topic = ResourceIdentifier.fromString(msg.topicName());

            ctx.verify(() -> {
                endpointConfig.assertCommandPublishTopicStructure(topic, commandTargetDeviceId, false, "setValue");
            });

            final String commandRequestId = topic.elementAt(4);
            final String command = topic.elementAt(5);

            // send response
            mqttClient.publish(
                    endpointConfig.getResponseTopic(commandTargetDeviceId, commandRequestId, HttpURLConnection.HTTP_OK),
                    Buffer.buffer(command + ": ok"),
                    qos,
                    false,
                    false);
        }, payload -> {
            return helper.sendCommand(
                    tenantId,
                    commandTargetDeviceId,
                    "setValue",
                    "text/plain",
                    payload,
                    // set "forceCommandRerouting" message property so that half the command are rerouted via the AMQP network
                    IntegrationTestSupport.newCommandMessageProperties(() -> counter.getAndIncrement() >= COMMANDS_TO_SEND/2),
                    200)
                    .map(response -> {
                        ctx.verify(() -> {
                            assertThat(response.getApplicationProperty(MessageHelper.APP_PROPERTY_DEVICE_ID, String.class)).isEqualTo(commandTargetDeviceId);
                            assertThat(response.getApplicationProperty(MessageHelper.APP_PROPERTY_TENANT_ID, String.class)).isEqualTo(tenantId);
                        });
                        return response;
                    });
        }, endpointConfig, COMMANDS_TO_SEND, qos);
    }

    private void testSendCommandSucceeds(
            final VertxTestContext ctx,
            final String commandTargetDeviceId,
            final Handler<MqttPublishMessage> commandConsumer,
            final Function<Buffer, Future<?>> commandSender,
            final MqttCommandEndpointConfiguration endpointConfig,
            final int totalNoOfCommandsToSend,
            final MqttQoS subscribeQos) throws InterruptedException {

        final VertxTestContext setup = new VertxTestContext();
        final Checkpoint ready = setup.checkpoint(2);

        helper.registry
        .addDeviceForTenant(tenantId, tenant, deviceId, password)
        .compose(ok -> connectToAdapter(IntegrationTestSupport.getUsername(deviceId, tenantId), password))
        .compose(ok -> createConsumer(tenantId, msg -> {
            // expect empty notification with TTD -1
            setup.verify(() -> assertThat(msg.getContentType()).isEqualTo(EventConstants.CONTENT_TYPE_EMPTY_NOTIFICATION));
            final TimeUntilDisconnectNotification notification = TimeUntilDisconnectNotification.fromMessage(msg).orElse(null);
            LOGGER.info("received notification [{}]", notification);
            setup.verify(() -> assertThat(notification).isNotNull());
            if (notification.getTtd() == -1) {
                ready.flag();
            }
        }))
        .compose(conAck -> subscribeToCommands(commandTargetDeviceId, commandConsumer, endpointConfig, subscribeQos))
        .setHandler(setup.succeeding(ok -> ready.flag()));

        assertThat(setup.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
        }

        final CountDownLatch commandsSucceeded = new CountDownLatch(totalNoOfCommandsToSend);
        final AtomicInteger commandsSent = new AtomicInteger(0);
        final AtomicLong lastReceivedTimestamp = new AtomicLong(0);
        final long start = System.currentTimeMillis();

        while (commandsSent.get() < totalNoOfCommandsToSend) {
            final CountDownLatch commandSent = new CountDownLatch(1);
            context.runOnContext(go -> {
                final Buffer msg = Buffer.buffer("value: " + commandsSent.getAndIncrement());
                commandSender.apply(msg).setHandler(sendAttempt -> {
                    if (sendAttempt.failed()) {
                        LOGGER.info("error sending command {}", commandsSent.get(), sendAttempt.cause());
                    } else {
                        lastReceivedTimestamp.set(System.currentTimeMillis());
                        commandsSucceeded.countDown();
                        if (commandsSucceeded.getCount() % 20 == 0) {
                            LOGGER.info("commands succeeded: {}", totalNoOfCommandsToSend - commandsSucceeded.getCount());
                        }
                    }
                    if (commandsSent.get() % 20 == 0) {
                        LOGGER.info("commands sent: " + commandsSent.get());
                    }
                    commandSent.countDown();
                });
            });

            commandSent.await();
        }

        final long timeToWait = totalNoOfCommandsToSend * 200;
        if (!commandsSucceeded.await(timeToWait, TimeUnit.MILLISECONDS)) {
            LOGGER.info("Timeout of {} milliseconds reached, stop waiting for commands to succeed", timeToWait);
        }
        if (lastReceivedTimestamp.get() == 0L) {
            // no message has been received at all
            lastReceivedTimestamp.set(System.currentTimeMillis());
        }
        final long commandsCompleted = totalNoOfCommandsToSend - commandsSucceeded.getCount();
        LOGGER.info("commands sent: {}, commands succeeded: {} after {} milliseconds",
                commandsSent.get(), commandsCompleted, lastReceivedTimestamp.get() - start);
        if (commandsCompleted == commandsSent.get()) {
            ctx.completeNow();
        } else {
            ctx.failNow(new IllegalStateException("did not complete all commands sent"));
        }
    }

    /**
     * Verifies that the adapter rejects malformed command messages sent by applications.
     * 
     * @param endpointConfig The endpoints to use for sending/receiving commands.
     * @param ctx The vert.x test context.
     * @throws InterruptedException if not all commands and responses are exchanged in time.
     */
    @ParameterizedTest(name = IntegrationTestSupport.PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("allCombinations")
    @Timeout(timeUnit = TimeUnit.SECONDS, value = 20)
    public void testSendCommandFailsForMalformedMessage(
            final MqttCommandEndpointConfiguration endpointConfig,
            final VertxTestContext ctx) throws InterruptedException {

        final VertxTestContext setup = new VertxTestContext();
        final Checkpoint ready = setup.checkpoint(3);

        final String commandTargetDeviceId = endpointConfig.isSubscribeAsGateway()
                ? helper.setupGatewayDeviceBlocking(tenantId, deviceId, 5)
                : deviceId;

        helper.registry
                .addDeviceForTenant(tenantId, tenant, deviceId, password)
                .compose(ok -> connectToAdapter(IntegrationTestSupport.getUsername(deviceId, tenantId), password))
                .compose(ok -> createConsumer(tenantId, msg -> {
                    // expect empty notification with TTD -1
                    setup.verify(() -> assertThat(msg.getContentType()).isEqualTo(EventConstants.CONTENT_TYPE_EMPTY_NOTIFICATION));
                    final TimeUntilDisconnectNotification notification = TimeUntilDisconnectNotification
                            .fromMessage(msg).orElse(null);
                    LOGGER.info("received notification [{}]", notification);
                    if (notification.getTtd() == -1) {
                        ready.flag();
                    }
                }))
                .compose(conAck -> subscribeToCommands(commandTargetDeviceId, msg -> {
                    setup.failNow(new IllegalStateException("should not have received command"));
                }, endpointConfig, MqttQoS.AT_MOST_ONCE))
                .setHandler(ctx.succeeding(ok -> ready.flag()));

        final AtomicReference<MessageSender> sender = new AtomicReference<>();
        final String linkTargetAddress = endpointConfig.getSenderLinkTargetAddress(tenantId);

        helper.applicationClientFactory.createGenericMessageSender(linkTargetAddress)
        .map(s -> {
            LOGGER.debug("created generic sender for sending commands [target address: {}]", linkTargetAddress);
            sender.set(s);
            ready.flag();
            return s;
        });

        assertThat(setup.awaitCompletion(15, TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
        }

        final Checkpoint failedAttempts = ctx.checkpoint(2);
        final String messageAddress = endpointConfig.getCommandMessageAddress(tenantId, commandTargetDeviceId);

        LOGGER.debug("sending command message lacking subject");
        final Message messageWithoutSubject = ProtonHelper.message("input data");
        messageWithoutSubject.setAddress(messageAddress);
        messageWithoutSubject.setMessageId("message-id");
        messageWithoutSubject.setReplyTo("reply/to/address");
        sender.get().sendAndWaitForOutcome(messageWithoutSubject).setHandler(ctx.failing(t -> {
            ctx.verify(() -> assertThat(t).isInstanceOf(ClientErrorException.class));
            failedAttempts.flag();
        }));

        LOGGER.debug("sending command message lacking message ID and correlation ID");
        final Message messageWithoutId = ProtonHelper.message("input data");
        messageWithoutId.setAddress(messageAddress);
        messageWithoutId.setSubject("setValue");
        messageWithoutId.setReplyTo("reply/to/address");
        sender.get().sendAndWaitForOutcome(messageWithoutId).setHandler(ctx.failing(t -> {
            ctx.verify(() -> assertThat(t).isInstanceOf(ClientErrorException.class));
            failedAttempts.flag();
        }));
    }
}
