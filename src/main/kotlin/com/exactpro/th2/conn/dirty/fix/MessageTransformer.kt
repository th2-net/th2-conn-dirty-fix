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

import com.exactpro.th2.conn.dirty.fix.FixField.FixGroup
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id.DEDUCTION
import io.netty.buffer.ByteBuf
import mu.KotlinLogging
import java.util.regex.Pattern
import kotlin.properties.Delegates.notNull

typealias RuleID = String
typealias Tag = Int

object MessageTransformer {
    private val logger = KotlinLogging.logger {}

    fun transform(message: ByteBuf, rule: Rule, unconditionally: Boolean = false): TransformResult? {
        logger.debug { "Applying rule: ${rule.name}" }

        val original = message.copy()

        val results = mutableListOf<ActionResult>().apply {
            for ((conditions, actions) in rule.transform) {
                if (unconditionally || conditions.all(message::contains)) {
                    this += transform(message, actions)
                }
            }
        }

        if (results.isEmpty()) {
            logger.debug("No transformations were applied")
            return null
        }

        results.forEach { logger.trace { "Applied transformation: $it" } }

        if (rule.transform.any(Transform::updateLength)) {
            message.updateLength()
            logger.trace { "Recalculated length" }
        }

        if (rule.transform.any(Transform::updateChecksum)) {
            message.updateChecksum()
            logger.trace { "Recalculated checksum" }
        }

        return TransformResult(rule.name, results, original)
    }

    private fun transform(message: ByteBuf, actions: List<Action>) = sequence {
        for (action in actions) {
            try {
                val context = when (val group = action.group) {
                    null -> message.fields
                    else -> group.find(message) ?: continue
                }

                action.set?.apply {
                    val tag = singleTag
                    val value = singleValue
                    val field = context.find { it.tag == tag }

                    if (field != null) {
                        field.value = singleValue
                        yield(ActionResult(tag, value, action))
                    }
                }

                action.add?.also { field ->
                    val tag = field.singleTag
                    val value = field.singleValue

                    action.before?.find(context)?.let { next ->
                        next.insertPrevious(tag, value)
                        yield(ActionResult(tag, value, action))
                    }

                    action.after?.find(context)?.let { previous ->
                        previous.insertNext(tag, value)
                        yield(ActionResult(tag, value, action))
                    }
                }

                action.move?.find(context)?.let { field ->
                    val tag = checkNotNull(field.tag) { "Field tag for move was empty" }
                    val value = field.value

                    action.before?.find(context)?.let { next ->
                        field.clear()
                        next.insertPrevious(tag, value)
                        yield(ActionResult(tag, value, action))
                    }

                    action.after?.find(context)?.let { previous ->
                        previous.insertNext(tag, value)
                        field.clear()
                        yield(ActionResult(tag, null, action))
                    }
                }

                action.remove?.find(context)?.let { field ->
                    val tag = checkNotNull(field.tag) { "Field tag for remove was empty" }
                    field.clear()
                    yield(ActionResult(tag, null, action))
                }

                action.replace?.find(context)?.let { field ->
                    val with = action.with!!
                    val tag = with.singleTag
                    val value = with.singleValue

                    field.tag = tag
                    field.value = value

                    yield(ActionResult(tag, value, action))
                }
            } catch (e: Exception) {
                logger.error(e) { "Error while applying action $action" }
                yield(
                    ActionResult(
                        -1,
                        null,
                        action,
                        ActionStatusDescription(
                            "Error while applying action: $action. Message: ${e.message}",
                            ActionStatus.FAIL
                        )
                    )
                )
            }
        }
    }
}

data class Group(
    val counter: Int,
    val delimiter: Int,
    val tags: Set<Int>,
) {
    init {
        check(counter != delimiter) { "'counter' is equal to 'delimiter': $counter" }
        check(counter !in tags) { "'tags' cannot contain 'counter' tag: $counter" }
        check(delimiter !in tags) { "'tags' cannot contain 'delimiter' tag: $delimiter" }
    }
}

data class Context(val groups: Map<String, Group> = mapOf())

@JsonTypeInfo(use = DEDUCTION)
@JsonSubTypes(Type(FieldSelector::class), Type(GroupSelector::class))
interface Selector {
    fun init(context: Context) = Unit
    fun find(message: ByteBuf): FixElement?
    fun find(fields: Iterable<FixField>): FixElement?
}

fun ByteBuf.contains(selector: Selector): Boolean = selector.find(this) != null
fun Iterable<FixField>.contains(selector: Selector): Boolean = selector.find(this) != null

data class FieldSelector(
    val tag: Tag?,
    @JsonAlias("one-of-tags", "tagOneOf") val tags: List<Tag>?,
    @JsonAlias("matching") val matches: Pattern,
) : Selector {
    init {
        require((tag != null) xor !tags.isNullOrEmpty()) { "Either 'tag' or 'one-of-tags' must be specified" }
    }

    @JsonIgnore private val predicate = matches.asMatchPredicate()

    override fun find(fields: Iterable<FixField>): FixField? = when (tags) {
        null -> fields.find { it.tag == tag && it.value?.run(predicate::test) ?: false }
        else -> fields.filter { it.tag in tags && it.value?.run(predicate::test) ?: false }.randomOrNull()
    }

    override fun find(message: ByteBuf): FixField? = find(message.fields)

    override fun toString() = buildString {
        tag?.apply { append("tag $tag") }
        tags?.apply { append("one of tags $tags") }
        append(" ~= /$matches/")
    }
}

data class GroupSelector(
    val group: String,
    @JsonAlias("contains", "where") val selectors: List<FieldSelector>,
) : Selector {
    private var counter by notNull<Int>()
    private var delimiter by notNull<Int>()
    private lateinit var tags: Iterable<Int>

    init {
        require(selectors.isNotEmpty()) { "group selector has no fields: $group" }
    }

    override fun init(context: Context) {
        val group = context.groups[group] ?: error("Unknown group: $group")
        counter = group.counter
        delimiter = group.delimiter
        tags = group.tags
    }

    override fun find(fields: Iterable<FixField>): FixGroup? {
        var group = fields.findGroup(counter, delimiter, tags)

        while (group != null) {
            if (selectors.all(group::contains)) return group
            group = group.next()
        }

        return null
    }

    override fun find(message: ByteBuf): FixGroup? = find(message.fields)

    override fun toString() = "group '$group' where ${selectors.joinToString(" && ")}"
}

data class FieldDefinition(
    val tag: Tag?,
    @JsonAlias("to", "equal") val value: String?,
    @JsonAlias("one-of-tags", "tagOneOf") val tags: List<Tag>?,
    @JsonAlias("valueOneOf", "to-one-of", "equal-one-of") val values: List<String>?,
) {
    init {
        require((tag != null) xor !tags.isNullOrEmpty()) { "Either 'tag' or 'one-of-tags' must be specified" }
        require((value != null) xor !values.isNullOrEmpty()) { "'to/equal' and 'equal-one-of/to-one-of' are mutually exclusive" }
    }

    @JsonIgnore val singleTag: Tag = tag ?: tags!!.random()
    @JsonIgnore val singleValue: String = value ?: values!!.random()

    override fun toString() = buildString {
        tag?.apply { append("tag $tag") }
        tags?.apply { append("one of $tags") }
        append(" = ")
        value?.apply { append("'$value'") }
        values?.apply { append("one of $values") }
    }
}

data class Action(
    val set: FieldDefinition? = null,
    val add: FieldDefinition? = null,
    val move: FieldSelector? = null,
    val replace: FieldSelector? = null,
    val remove: FieldSelector? = null,
    val with: FieldDefinition? = null,
    val before: FieldSelector? = null,
    val after: FieldSelector? = null,
    @JsonAlias("in") val group: GroupSelector? = null,
) {
    init {
        val operations = listOfNotNull(set, add, move, remove, replace)

        require(operations.isNotEmpty()) { "Action must have at least one operation" }
        require(operations.size == 1) { "Action has more than one operation" }

        require(set == null && remove == null || with == null && before == null && after == null) {
            "'set'/'remove' operations cannot have 'with', 'before', 'after' options"
        }

        require(add == null && move == null || with == null) {
            "'add'/'move' operations cannot have 'with' option"
        }

        require(replace == null || with != null) {
            "'replace' operation requires 'with' option"
        }

        require(before == null || after == null) {
            "'before' and 'after' options are mutually exclusive"
        }

        require(add == null && move == null || before != null || after != null) {
            "'add'/'move' option requires 'before' or 'after' option"
        }
    }

    fun init(context: Context) {
        move?.init(context)
        replace?.init(context)
        remove?.init(context)
        before?.init(context)
        after?.init(context)
        group?.init(context)
    }

    override fun toString() = buildString {
        set?.apply { append("set $this") }
        add?.apply { append("add $this") }
        move?.apply { append("move $this") }
        replace?.apply { append("replace $this") }
        remove?.apply { append("remove $this") }
        with?.apply { append(" with $this") }
        before?.apply { append(" before $this") }
        after?.apply { append(" after $this") }
        group?.apply { append(" on $this") }
    }
}

data class Transform(
    @JsonAlias("when") val conditions: List<Selector>,
    @JsonAlias("then") val actions: List<Action>,
    @JsonAlias("update-length") val updateLength: Boolean = true,
    @JsonAlias("update-checksum") val updateChecksum: Boolean = true,
) {
    init {
        require(actions.isNotEmpty()) { "Transformation must have at least one action" }
    }

    fun init(context: Context) {
        conditions.forEach { it.init(context) }
        actions.forEach { it.init(context) }
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

    fun init(context: Context) = transform.forEach { it.init(context) }

    override fun toString() = buildString {
        appendLine("name: $name")
        appendLine("transforms: ")
        transform.forEach { appendLine("    $it") }
    }
}

data class TransformResult(val rule: RuleID, val results: List<ActionResult>, val message: ByteBuf)
data class ActionResult(val tag: Tag, val value: String?, val action: Action, val statusDesc: ActionStatusDescription = SUCCESS_ACTION_DESCRIPTION)
data class ActionStatusDescription(val description: String? = null, val status: ActionStatus)
enum class ActionStatus { SUCCESS, FAIL; }
private val SUCCESS_ACTION_DESCRIPTION = ActionStatusDescription(status = ActionStatus.SUCCESS)