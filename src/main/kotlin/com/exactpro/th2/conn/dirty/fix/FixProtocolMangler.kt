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

package com.exactpro.th2.conn.dirty.fix

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.Event.Status.PASSED
import com.exactpro.th2.common.event.EventUtils.createMessageBean
import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolMangler
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolManglerFactory
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolManglerSettings
import com.fasterxml.jackson.annotation.JsonAlias
import com.google.auto.service.AutoService
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil

class FixProtocolMangler(context: IContext<IProtocolManglerSettings>) : IProtocolMangler {
    private val rules = (context.settings as FixProtocolManglerSettings).rule

    override fun onOutgoing(message: ByteBuf, metadata: MutableMap<String, String>): Event? {
        val original = message.copy()
        val (id, actions) = MessageTransformer.transform(message, rules) ?: return null


        //FIXME: Replace metadata info to event info of transforms
        metadata["CorruptionType"] = id.toString()
        metadata["CorruptedTag"] = actions[0].tag.toString()
        actions[0].value?.let { metadata["CorruptedValue"] = it }


        return Event.start().apply {
            name("Message mangled")
            type("Mangle")
            status(PASSED)
            actions.forEach { bodyData(createMessageBean(it.toString())) }
            bodyData(createMessageBean("Original message:"))
            bodyData(createMessageBean(ByteBufUtil.prettyHexDump(original)))
        }
    }
}

@AutoService(IProtocolManglerFactory::class)
class FixProtocolManglerFactory : IProtocolManglerFactory {
    override val name = "demo-fix-mangler"
    override val settings = FixProtocolManglerSettings::class.java
    override fun create(context: IContext<IProtocolManglerSettings>) = FixProtocolMangler(context)
}

class FixProtocolManglerSettings(@JsonAlias("rules") val rule: List<Rule> = emptyList()) : IProtocolManglerSettings