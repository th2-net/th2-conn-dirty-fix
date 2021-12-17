package com.exactpro.th2;

import org.jetbrains.annotations.NotNull;

public class FixHandlerFactory implements IProtocolHandlerFactory{

    @NotNull
    @Override
    public String getProtocol() {
        return null;
    }

    @NotNull
    @Override
    public Class<? extends IProtocolHandlerSettings> getSettings() {
        return null;
    }

    @NotNull
    @Override
    public IProtocolHandler create(@NotNull IProtocolHandlerContext context) {
        return null;
    }
}

