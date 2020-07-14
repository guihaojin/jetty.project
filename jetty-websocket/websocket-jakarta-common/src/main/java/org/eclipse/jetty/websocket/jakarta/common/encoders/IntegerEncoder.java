//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.common.encoders;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;

/**
 * Default encoder for {@link Integer} to {@link jakarta.websocket.Encoder.Text} Message encoder
 */
public class IntegerEncoder extends AbstractEncoder implements Encoder.Text<Integer>
{
    @Override
    public String encode(Integer object) throws EncodeException
    {
        if (object == null)
        {
            return null;
        }
        return object.toString();
    }
}