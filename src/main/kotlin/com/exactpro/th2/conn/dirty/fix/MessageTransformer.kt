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

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import io.netty.buffer.ByteBuf
import mu.KotlinLogging
import java.util.regex.Pattern
import kotlin.text.Charsets.UTF_8

typealias RuleID = Int

object MessageTransformer {
    private val logger = KotlinLogging.logger {}

    fun transform(message: ByteBuf, rules: List<Rule>): List<Action> {
        logger.debug { "Processing message: ${message.toString(UTF_8)}" }

        val executed = mutableListOf<Action>()

        val targetRule = rules.filter { rule ->
            rule.transform.any { transform ->
                transform.conditions.any { it.matches(message) }
            }
        }.randomOrNull()

        if (targetRule == null) {
            logger.debug { "No transformation were applied" }
            return executed
        }

        for ((conditions, actions) in targetRule.transform) {
            if (!conditions.all { it.matches(message) }) {
                continue
            }

            actions.forEach { action ->
                action.set?.apply {
                    if (message.setField(tag, value)) {
                        executed += action
                    }
                }

                action.add?.also { field ->
                    action.before?.find(message)?.let { next ->
                        next.insertPrevious(field.tag, field.value)
                        executed += action
                    }

                    action.after?.find(message)?.let { previous ->
                        previous.insertNext(field.tag, field.value)
                        executed += action
                    }
                }

                action.remove?.find(message)?.let { field ->
                    field.clear()
                    executed += action
                }

                action.replace?.find(message)?.let { field ->
                    val with = action.with!!
                    field.tag = with.tag
                    field.value = with.value
                    executed += action
                }
            }
        }

        executed.forEach { logger.debug { "Applied transformation: $it" } }

        if (targetRule.transform.any(Transform::updateLength)) {
            message.updateLength()
            logger.debug { "Recalculated length" }
        }

        if (targetRule.transform.any(Transform::updateChecksum)) {
            message.updateChecksum()
            logger.debug { "Recalculated checksum" }
        }

        return executed
    }
}

data class FieldSelector(
    val tag: Int,
    val matches: Pattern,
) {
    @JsonIgnore private val predicate = matches.asMatchPredicate()

    fun matches(message: ByteBuf): Boolean = find(message) != null

    fun find(message: ByteBuf): FixField? = message.findField {
        it.tag == this.tag && it.value?.run(this.predicate::test) ?: false
    }

    override fun toString() = buildString {
        append("tag $tag")
        append(" ~= /$matches/")
    }
}

data class FieldDefinition(
    val tag: Int,
    val value: String,
) {
    override fun toString() = "tag $tag = '$value'"
}

data class Action(
    val set: FieldDefinition? = null,
    val add: FieldDefinition? = null,
    val replace: FieldSelector? = null,
    val remove: FieldSelector? = null,
    val with: FieldDefinition? = null,
    val before: FieldSelector? = null,
    val after: FieldSelector? = null,
) {
    init {
        val operations = listOfNotNull(set, add, remove, replace)

        require(operations.isNotEmpty()) { "Action must have at least one operation" }
        require(operations.size == 1) { "Action has more than one operation" }

        require(set == null && remove == null || with == null && before == null && after == null) {
            "'set'/'remove' operations cannot have 'with', 'before', 'after' options"
        }

        require(add == null || with == null) {
            "'add' operations cannot have 'with' option"
        }

        require(replace == null || with != null) {
            "'replace' operation requires 'with' option"
        }

        require(before == null || after == null) {
            "'before' and 'after' options are mutually exclusive"
        }

        require(add == null || before != null || after != null) {
            "'add' option requires 'before' or 'after' option"
        }
    }

    override fun toString() = buildString {
        set?.apply { append("set $this") }
        add?.apply { append("add $this") }
        replace?.apply { append("replace $this") }
        remove?.apply { append("remove $this") }
        with?.apply { append(" with $this") }
        before?.apply { append(" before $this") }
        after?.apply { append(" after $this") }
    }
}


data class Transform(
    @JsonAlias("when") val conditions: List<FieldSelector>,
    @JsonAlias("then") val actions: List<Action>,
    @JsonAlias("update-length") val updateLength: Boolean = true,
    @JsonAlias("update-checksum") val updateChecksum: Boolean = true,
) {
    init {
        require(conditions.isNotEmpty()) { "Transformation must have at least one condition" }
        require(actions.isNotEmpty()) { "Transformation must have at least one action" }
    }

    override fun toString() = buildString {
        appendLine("when")
        conditions.forEach { appendLine("    $it") }
        appendLine("then")
        actions.forEach { appendLine("    $it") }
    }
}

data class Rule(
    val ruleID: RuleID,
    val transform: List<Transform>
) {
    init {
        require(transform.isNotEmpty()) { "Rule must have at least one transform" }
    }

    override fun toString() = buildString {
        appendLine("id: $ruleID")
        appendLine("transforms: ")
        transform.forEach { appendLine("    $it") }
    }
}