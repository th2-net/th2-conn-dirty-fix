package com.exactpro.th2;

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext;
import com.exactpro.th2.conn.dirty.tcp.core.api.IFactory;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandler;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class FixHandlerFactory implements IFactory<IProtocolHandler, IProtocolHandlerSettings> {


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
        return new FixHandler(Objects.requireNonNull(iContext.getChannel()), Objects.requireNonNull(iContext.getSettings()));
    }
}

