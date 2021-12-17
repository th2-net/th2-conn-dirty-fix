package com.exactpro.th2;


import com.exactpro.th2.common.event.Event;
import com.exactpro.th2.common.schema.dictionary.DictionaryType;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

public class FixHandlerContext implements IProtocolHandlerContext{
    @NotNull
    @Override
    public IClient getClient() {
        return null;
    }

    @NotNull
    @Override
    public IProtocolHandlerSettings getSettings() {
        return null;
    }

    @NotNull
    @Override
    public InputStream get(@NotNull DictionaryType dictionary) {
        return null;
    }

    @Override
    public void send(@NotNull Event event) {

    }
}
