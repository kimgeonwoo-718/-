package com.spellkeyboard.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationMemoryTest {

    @Test
    fun `기억한 것만 빼고 새로 번역할 것을 고른다`() {
        val memory = TranslationMemory()
        val sentences = listOf("안녕하세요", "저는 좋아요", "안녕하세요")
        assertEquals(listOf("안녕하세요", "저는 좋아요"), memory.missing(sentences))
        memory.remember("안녕하세요", "Hello")
        assertEquals(listOf("저는 좋아요"), memory.missing(sentences))
    }

    @Test
    fun `아직 번역 못 한 문장은 원문을 둔다`() {
        val memory = TranslationMemory()
        memory.remember("안녕하세요", "Hello")
        assertEquals("Hello 저는 좋아요", memory.assemble(listOf("안녕하세요", "저는 좋아요")))
        memory.remember("저는 좋아요", "I'm good")
        assertEquals("Hello I'm good", memory.assemble(listOf("안녕하세요", "저는 좋아요")))
    }

    @Test
    fun `언어가 바뀌면 통째로 버린다`() {
        val memory = TranslationMemory()
        memory.remember("안녕하세요", "Hello")
        memory.clear()
        assertNull(memory.cached("안녕하세요"))
    }

    @Test
    fun `오래된 것부터 버린다`() {
        val memory = TranslationMemory(capacity = 2)
        memory.remember("하나", "one")
        memory.remember("둘", "two")
        memory.remember("셋", "three")
        assertNull(memory.cached("하나"))
        assertEquals("three", memory.cached("셋"))
    }
}
