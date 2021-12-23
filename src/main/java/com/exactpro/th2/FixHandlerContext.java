package com.exactpro.th2;

import com.exactpro.th2.common.event.Event;
import com.exactpro.th2.common.schema.dictionary.DictionaryType;
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel;
import com.exactpro.th2.conn.dirty.tcp.core.api.IContext;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

public class FixHandlerContext implements IContext<IProtocolHandlerSettings> {

    @NotNull
    @Override
    public IChannel getChannel() {
        return null;
    }

    @Override
    public IProtocolHandlerSettings getSettings() {
        return new FixHandlerSettings();
    }

    @NotNull
    @Override
    public InputStream get(@NotNull DictionaryType dictionaryType) {
        return null;
    }

    @Override
    public void send(@NotNull Event event) {
    }
}
