/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.hono.util;

import java.util.Arrays;
import java.util.List;

/**
 * Constants &amp; utility methods used throughout the Registration API.
 */
public final class RegistrationConstants extends RequestResponseApiConstants {

    /**
     * The AMQP 1.0 <em>subject</em> to use for the <em>assert device registration</em> operation.
     */
    public static final String ACTION_ASSERT     = "assert";
    /**
     * The AMQP 1.0 <em>subject</em> to use for the <em>register device</em> operation.
     */
    public static final String ACTION_REGISTER   = "register";
    /**
     * The AMQP 1.0 <em>subject</em> to use for the <em>get registration information</em> operation.
     */
    public static final String ACTION_GET        = "get";
    /**
     * The AMQP 1.0 <em>subject</em> to use for the <em>deregister device</em> operation.
     */
    public static final String ACTION_DEREGISTER = "deregister";
    /**
     * The AMQP 1.0 <em>subject</em> to use for the <em>update device registration</em> operation.
     */
    public static final String ACTION_UPDATE     = "update";

    /**
     * The name of the field in a response to the <em>get registration information</em> operation
     * that contains a device's registration information.
     */
    public static final String FIELD_DATA         = "data";

    /**
     * The name of the field in a device's registration information that contains
     * the identifier of the gateway that it is connected to (either as string value or inside a JSON array).
     * Note, that it is only possible to either set the 'via' or the 'memberOf' property since gateways of gateways
     * are currently not supported.
     */
    public static final String FIELD_VIA = "via";

    /**
     * The name of the field in the device registration information that contains the identifier of the gateways groups
     * that the device is connected to (either as String value or inside a JSON array).
     * Note, that is is only possible to either set the 'viaGroups' or the 'memberOf' property since groups of groups
     * are currently not supported.
     */
    public static final String FIELD_VIA_GROUPS = "viaGroups";

    /**
     * The name of the field in a device registration information that contains the identifier of the names
     * of the groups in which the device is member of (either as string value or inside a JSON array).
     * Note, that it is only possible to either set the 'via' or the 'memberOf' property since groups of groups
     * are currently not supported.
     */
    public static final String FIELD_MEMBER_OF = "memberOf";

    /**
     * The name of the Device Registration API endpoint.
     */
    public static final String REGISTRATION_ENDPOINT = "registration";

    /**
     * The vert.x event bus address to which inbound registration messages are published.
     */
    public static final String EVENT_BUS_ADDRESS_REGISTRATION_IN = "registration.in";

    private static final List<String> ACTIONS = Arrays.asList(ACTION_ASSERT, ACTION_REGISTER,
            ACTION_GET, ACTION_DEREGISTER, ACTION_UPDATE);

    private RegistrationConstants() {
        // prevent instantiation
    }

    /**
     * Checks if a given string represents a valid action.
     *
     * @param action The string to check.
     * @return {@code true} if the given string is a supported action.
     */
    public static boolean isValidAction(final String action) {
        if (action == null) {
            return false;
        } else {
            return ACTIONS.contains(action);
        }
    }
}
