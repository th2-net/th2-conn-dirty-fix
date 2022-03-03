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

typealias RuleID = String
typealias Tag = Int

object MessageTransformer {
    private val logger = KotlinLogging.logger {}

    fun transform(message: ByteBuf, rules: List<Rule>): TransformResult? {
        logger.debug { "Processing message: ${message.toString(UTF_8)}" }

        val executed = mutableListOf<ActionResult>()

        val targetRule = rules.filter { rule ->
            rule.transform.any { transform ->
                transform.conditions.any { it.matches(message) }
            }
        }.randomOrNull()

        if (targetRule == null) {
            logger.debug { "No transformation were applied" }
            return null
        }

        for ((conditions, actions) in targetRule.transform) {
            if (!conditions.all { it.matches(message) }) {
                continue
            }

            actions.forEach { action ->
                action.set?.apply {
                    val tag = getSingleTag()
                    val value = getSingleValue()
                    if (message.setField(tag, value)) {
                        executed += ActionResult(tag, value, action)
                    }
                }

                action.add?.also { field ->
                    val tag = field.getSingleTag()
                    val value = field.getSingleValue()
                    action.before?.find(message)?.let { next ->
                        next.insertPrevious(tag, value)
                        executed += ActionResult(tag, value, action)
                    }

                    action.after?.find(message)?.let { previous ->
                        previous.insertNext(tag, value)
                        executed += ActionResult(tag, value, action)
                    }
                }

                action.remove?.find(message)?.let { field ->
                    val tag = checkNotNull(field.tag) { "Field tag for remove was empty" }
                    field.clear()
                    executed += ActionResult(tag, null, action)
                }

                action.replace?.find(message)?.let { field ->
                    val with = action.with!!
                    val tag = with.getSingleTag()
                    val value = with.getSingleValue()
                    field.tag = tag
                    field.value = value
                    executed += ActionResult(tag, value, action)
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

        return TransformResult(targetRule.name, executed)
    }
}

fun FieldDefinition.getSingleTag(): Tag {
    if (tag != null) {
        return tag
    }
    return checkNotNull(tagOneOf) { "At last one tag need to be defined" }.random()
}

fun FieldDefinition.getSingleValue(): String {
    if (value != null) {
        return value
    }
    return checkNotNull(valueOneOf) { "At last one value need to be defined" }.random()
}

data class FieldSelector(
    val tag: Tag?,
    val tagOneOf: List<Tag>?,
    val matches: Pattern,
) {

    init {
        require(tag != null || tagOneOf != null && tagOneOf.isNotEmpty()) { "Tag must be defined" }
    }

    @JsonIgnore private val predicate = matches.asMatchPredicate()

    fun matches(message: ByteBuf): Boolean = find(message) != null

    fun find(message: ByteBuf): FixField? {
         when {
             tag != null -> return message.findField {
                 it.tag == this.tag && it.value?.run(this.predicate::test) ?: false
             }
             tagOneOf != null -> {
                 val foundFields = message.findAll {
                     tagOneOf.contains(it.tag) && it.value?.run(this.predicate::test) ?: false
                 }
                 if (foundFields.isEmpty()) {
                     return null
                 }
                 return foundFields.random()
             }
        }
        return null
    }

    override fun toString() = buildString {
        tag?.apply { append("tag $tag") }
        tagOneOf?.apply { append("one of tags $tag") }
        append(" ~= /$matches/")
    }
}

data class FieldDefinition(
    val tag: Tag?,
    val value: String?,
    val tagOneOf: List<Tag>?,
    val valueOneOf: List<String>?
) {

    init {
        require(tag != null || tagOneOf != null && tagOneOf.isNotEmpty()) { "Tag must be defined" }
        require(value != null || valueOneOf != null && valueOneOf.isNotEmpty()) { "Transformation must have at least one action" }
    }

    override fun toString() = buildString {
        tag?.apply { append("tag $tag") }
        tagOneOf?.apply { append("one of tags $tagOneOf") }
        append(" = ")
        value?.apply { append("'$value'") }
        valueOneOf?.apply { append("one of $valueOneOf") }
    }
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
    val name: RuleID,
    val transform: List<Transform>,
) {
    init {
        require(transform.isNotEmpty()) { "Rule must have at least one transform" }
    }

    override fun toString() = buildString {
        appendLine("name: $name")
        appendLine("transforms: ")
        transform.forEach { appendLine("    $it") }
    }
}

data class TransformResult(val rule: RuleID, val actions: List<ActionResult>)
data class ActionResult(val tag: Tag, val value: String?, val action: Action)