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

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.text.Charsets.UTF_8

class TestMessageTransformer {
    @Test fun `set field`() {
        val buffer = MESSAGE.toBuffer()
        val transform = set(49 to "abc") onlyIf (35 matches "A")
        val description = MessageTransformer.transform(buffer, transform).joinToString(System.lineSeparator())
        assertEquals(description, "set tag 49 = 'abc'")
        assertEquals("8=FIX.4.2|9=62|35=A|49=abc|56=CLIENT|34=177|52=20090107-18:15:16|98=0|108=30|10=138|", buffer.asString())
    }

    @Test fun `add field before`() {
        val buffer = MESSAGE.toBuffer()
        val transform = add(123 eq "abc") before (34 matches "177") onlyIf (35 matches "A")
        val description = MessageTransformer.transform(buffer, transform).joinToString(System.lineSeparator())
        assertEquals(description, "add tag 123 = 'abc' before tag 34 ~= /177/")
        assertEquals("8=FIX.4.2|9=73|35=A|49=SERVER|56=CLIENT|123=abc|34=177|52=20090107-18:15:16|98=0|108=30|10=055|", buffer.asString())
    }

    @Test fun `add field after`() {
        val buffer = MESSAGE.toBuffer()
        val transform = add(124 eq "cde") after (34 matches "177") onlyIf (35 matches "A")
        val description = MessageTransformer.transform(buffer, transform).joinToString(System.lineSeparator())
        assertEquals(description, "add tag 124 = 'cde' after tag 34 ~= /177/")
        assertEquals("8=FIX.4.2|9=73|35=A|49=SERVER|56=CLIENT|34=177|124=cde|52=20090107-18:15:16|98=0|108=30|10=062|", buffer.asString())
    }

    @Test fun `replace field`() {
        val buffer = MESSAGE.toBuffer()
        val transform = replace(98 matching "0") with (100 eq "1") onlyIf (35 matches "A")
        val description = MessageTransformer.transform(buffer, transform).joinToString(System.lineSeparator())
        assertEquals(description, "replace tag 98 ~= /0/ with tag 100 = '1'")
        assertEquals("8=FIX.4.2|9=66|35=A|49=SERVER|56=CLIENT|34=177|52=20090107-18:15:16|100=1|108=30|10=096|", buffer.asString())
    }

    @Test fun `remove field`() {
        val buffer = MESSAGE.toBuffer()
        val transform = remove(52 matching ".*") onlyIf (35 matches "A")
        val description = MessageTransformer.transform(buffer, transform).joinToString(System.lineSeparator())
        assertEquals(description, "remove tag 52 ~= /.*/")
        assertEquals("8=FIX.4.2|9=44|35=A|49=SERVER|56=CLIENT|34=177|98=0|108=30|10=044|", buffer.asString())
    }

    @Test fun `non-matching condition`() {
        val buffer = MESSAGE.toBuffer()
        val transform = remove(52 matching ".*") onlyIf (35 matches "B")
        assertTrue(MessageTransformer.transform(buffer, transform).isEmpty())
        assertEquals("8=FIX.4.2|9=65|35=A|49=SERVER|56=CLIENT|34=177|52=20090107-18:15:16|98=0|108=30|10=062|", buffer.asString())
    }

    companion object {
        private const val MESSAGE = "8=FIX.4.2|9=65|35=A|49=SERVER|56=CLIENT|34=177|52=20090107-18:15:16|98=0|108=30|10=062|"
        private fun String.toBuffer() = Unpooled.buffer().writeBytes(replace('|', SOH_CHAR).toByteArray(UTF_8))
        private fun ByteBuf.asString() = toString(UTF_8).replace(SOH_CHAR, '|')
        private fun field(tag: Int, value: String) = FieldDefinition(tag, value)
        private fun select(tag: Int, pattern: String) = FieldSelector(tag, pattern.toPattern())
        private infix fun Int.eq(value: String) = field(this, value)
        private infix fun Int.to(value: String) = field(this, value)
        private infix fun Int.matches(pattern: String) = select(this, pattern)
        private infix fun Int.matching(pattern: String) = select(this, pattern)
        private fun set(field: FieldDefinition) = Action(set = field)
        private fun add(field: FieldDefinition) = field
        private fun replace(field: FieldSelector) = field
        private fun remove(fieldSelector: FieldSelector) = Action(remove = fieldSelector)
        private infix fun FieldDefinition.before(field: FieldSelector) = Action(add = this, before = field)
        private infix fun FieldDefinition.after(field: FieldSelector) = Action(add = this, after = field)
        private infix fun FieldSelector.with(field: FieldDefinition) = Action(replace = this, with = field)
        private infix fun Action.onlyIf(condition: FieldSelector) = listOf(Transform(listOf(condition), listOf(this)))
    }
}