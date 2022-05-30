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
import com.exactpro.th2.common.event.bean.IRow
import com.exactpro.th2.common.event.bean.builder.TableBuilder
import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolMangler
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolManglerFactory
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolManglerSettings
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.auto.service.AutoService
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import mu.KotlinLogging

private val LOGGER = KotlinLogging.logger {}

private val MAPPER = YAMLMapper.builder()
    .addModule(KotlinModule(nullIsSameAsDefault = true))
    .build()

private const val RULE_NAME_PROPERTY = "rule-name"
private const val RULE_ACTIONS_PROPERTY = "rule-actions"

class FixProtocolMangler(context: IContext<IProtocolManglerSettings>) : IProtocolMangler {
    private val rules = (context.settings as FixProtocolManglerSettings).rules

    override fun onOutgoing(message: ByteBuf, metadata: MutableMap<String, String>): Event? {
        var original = message

        val (rule, actions) = when {
            RULE_NAME_PROPERTY in metadata -> {
                val name = metadata[RULE_NAME_PROPERTY]
                val rule = rules.find { it.name == name } ?: throw IllegalArgumentException("No rule with name: $name")
                original = message.copy()
                MessageTransformer.transform(message, rule, true)
            }
            RULE_ACTIONS_PROPERTY in metadata -> {
                val actions = try {
                    MAPPER.readValue<List<Action>>(metadata[RULE_ACTIONS_PROPERTY]!!)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid '$RULE_ACTIONS_PROPERTY' value", e)
                }

                original = message.copy()
                val results = MessageTransformer.transform(message, actions).toList()

                if (results.isNotEmpty()) {
                    message.updateLength()
                    LOGGER.debug { "Recalculated length" }
                    message.updateChecksum()
                    LOGGER.debug { "Recalculated checksum" }
                }

                TransformResult("custom", results)
            }
            else -> {
                if (rules.isEmpty()) return null
                original = message.copy()
                MessageTransformer.transform(message, rules) ?: return null
            }
        }

        if (actions.isEmpty()) {
            LOGGER.debug("No transformations were applied")
            return null
        }

        return Event.start().apply {
            name("Message mangled")
            type("Mangle")
            status(PASSED)

            bodyData(createMessageBean("Original message:"))
            bodyData(createMessageBean(ByteBufUtil.prettyHexDump(original)))

            TableBuilder<ActionRow>().run {
                actions.forEach { row(ActionRow(rule, it.tag, it.value, it.action.toString())) }
                bodyData(build())
            }
        }
    }
}

@AutoService(IProtocolManglerFactory::class)
class FixProtocolManglerFactory : IProtocolManglerFactory {
    override val name = "demo-fix-mangler"
    override val settings = FixProtocolManglerSettings::class.java
    override fun create(context: IContext<IProtocolManglerSettings>) = FixProtocolMangler(context)
}

class FixProtocolManglerSettings(val rules: List<Rule> = emptyList()) : IProtocolManglerSettings

private data class ActionRow(
    val corruptionType: String,
    val corruptedTag: Int,
    val corruptedValue: String?,
    val corruptionDescription: String,
) : IRow