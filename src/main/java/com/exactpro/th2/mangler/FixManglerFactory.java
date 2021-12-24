package com.exactpro.th2.mangler;

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolMangler;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolManglerFactory;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolManglerSettings;
import com.google.auto.service.AutoService;
import org.jetbrains.annotations.NotNull;

@AutoService(IProtocolManglerFactory.class)
public class FixManglerFactory implements IProtocolManglerFactory {
    @NotNull
    @Override
    public String getName() {
        return FixManglerFactory.class.getSimpleName();
    }

    @NotNull
    @Override
    public Class<? extends IProtocolManglerSettings> getSettings() {
        return FixManglerSettings.class;
    }

    @Override
    public IProtocolMangler create(@NotNull IContext<IProtocolManglerSettings> iContext) {
        return new FixMangler();
    }
}
