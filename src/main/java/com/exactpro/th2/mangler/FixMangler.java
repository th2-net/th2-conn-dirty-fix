package com.exactpro.th2.mangler;

import com.exactpro.th2.common.event.Event;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolMangler;
import com.google.auto.service.AutoService;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

@AutoService(IProtocolMangler.class)
public class FixMangler implements IProtocolMangler {
    @Override
    public void afterOutgoing(@NotNull ByteBuf byteBuf, @NotNull Map<String, String> map) {

    }

    @Override
    public void close() {

    }

    @Override
    public void onClose() {

    }

    @Override
    public void onIncoming(@NotNull ByteBuf byteBuf, @NotNull Map<String, String> map) {

    }

    @Override
    public void onOpen() {

    }

    @Nullable
    @Override
    public Event onOutgoing(@NotNull ByteBuf byteBuf, @NotNull Map<String, String> map) {
        return null;
    }
}
