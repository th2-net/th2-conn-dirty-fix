/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2;

import java.security.Security;

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandler;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerFactory;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings;
import com.google.auto.service.AutoService;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jetbrains.annotations.NotNull;

@AutoService(IProtocolHandlerFactory.class)
public class FixHandlerFactory implements IProtocolHandlerFactory {
    static {
        // Init security when class is loaded
        Security.setProperty("crypto.policy", "unlimited");
        Security.addProvider(new BouncyCastleProvider());
    }
    @NotNull
    @Override
    public Class<? extends IProtocolHandlerSettings> getSettings() {
        return FixHandlerSettings.class;
    }

    @NotNull
    @Override
    public String getName() {
        return FixHandlerFactory.class.getSimpleName();
    }

    @Override
    public IProtocolHandler create(@NotNull IContext<IProtocolHandlerSettings> iContext) {
        return new FixHandler(iContext);
    }
}

