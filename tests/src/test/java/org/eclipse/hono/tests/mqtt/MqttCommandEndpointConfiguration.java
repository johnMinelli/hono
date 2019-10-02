/**
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
 */


package org.eclipse.hono.tests.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.hono.tests.CommandEndpointConfiguration;
import org.eclipse.hono.util.ResourceIdentifier;

/**
 * Configuration properties for defining variants of Command &amp; Control
 * related test scenarios.
 *
 */
public class MqttCommandEndpointConfiguration extends CommandEndpointConfiguration {

    /**
     * Creates a new configuration.
     * 
     * @param useGatewayDevice {@code true} if the device connecting to the adapter is a gateway.
     * @param useLegacySouthboundEndpoint {@code true} if the device uses the legacy command endpoint name.
     * @param useLegacyNorthboundEndpoint {@code true} if the application uses the legacy command endpoint name.
     */
    public MqttCommandEndpointConfiguration(
            final boolean useGatewayDevice,
            final boolean useLegacySouthboundEndpoint,
            final boolean useLegacyNorthboundEndpoint) {

        super(useGatewayDevice, useLegacySouthboundEndpoint, useLegacyNorthboundEndpoint);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return String.format("gateway device: %s, southbound endpoint: %s, northbound endpoint: %s, topic filter: %s",
                isGatewayDevice(), getSouthboundEndpoint(), getNorthboundEndpoint(), getCommandTopicFilter());
    }

    /**
     * Gets the topic filter that devices use for subsrcibing to commands.
     * 
     * @return The filter.
     */
    public final String getCommandTopicFilter() {
        return String.format(
                "%s/%s/req/#", getSouthboundEndpoint(), "+/+");
    }

    /**
     * Gets the name of the topic that a device uses for publishing the response to a command.
     * 
     * @param deviceId The identifier of the device.
     * @param requestId The request identifier from the command.
     * @param status The status code indicating the outcome of processing the command.
     * @return The topic name.
     */
    public final String getResponseTopic(final String deviceId, final String requestId, final int status) {
        return String.format(
                "%s///res/%s/%d",
                getSouthboundEndpoint(), requestId, status);
    }

    void assertCommandPublishTopicStructure(
            final ResourceIdentifier topic,
            final String expectedCommandTarget,
            final boolean isOneWayCommand,
            final String expectedCommandName) {

        assertThat(topic).isNotNull();

        assertThat(topic.getEndpoint())
        .as("command topic contains correct endpoint")
        .isEqualTo(getSouthboundEndpoint());

        assertThat(topic.getTenantId())
        .as("command topic does not contain tenant ID")
        .isNull();

        if (isGatewayDevice()) {
            assertThat(topic.getResourceId())
            .as("command topic contains device ID")
            .isEqualTo(expectedCommandTarget);
        } else {
            assertThat(topic.getResourceId())
            .as("command topic does not contain device ID")
            .isNull();
        }

        if (isOneWayCommand) {
            assertThat(topic.elementAt(4))
            .as("one-way command topic does not contain request ID")
            .isNull();
        } else {
            assertThat(topic.elementAt(4))
            .as("command topic contains request ID")
            .isNotNull();
        }

        assertThat(topic.elementAt(5))
        .as("command topic contains command name")
        .isEqualTo(expectedCommandName);

    }
}
