package com.github.quillraven.fleks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.measureTime

internal class TagTest {

    private object TagTestComponent : TagComponent<TagTestComponent>()

    enum class TagTestEnum : TrackableComponentType<TagTestEnum> by TagComponent() {
        A,
        B,
        C
    }

    private  class RegularTestComponent : Component<RegularTestComponent> {
        override fun type() = RegularTestComponent
        companion object : ComponentType<RegularTestComponent>()
    }

    private val testWorld = configureWorld { }

    private fun World.addRegular(n: Int) = repeat(n) { i ->
        entity { it += RegularTestComponent() }
    }

    private fun World.addTag(n: Int) = repeat(n) {
        entity { it += TagTestComponent }
    }

    @Test
    fun testMemory() = with(testWorld) {
        repeat(10) {
            println(
                "Regular components: ${
                    measureTime {
                        addRegular(1000)
                    }
                }"
            )
            println(
                "Tags: ${
                    measureTime {
                        addTag(1000)
                    }
                }"
            )
        }
    }

    @Test
    fun testTagAddRemoveAndFamily() = with (testWorld) {

        val f = family {
            all(TagTestComponent)
        }

        val enumFamily = family {
            all(TagTestEnum.A)
            any(TagTestEnum.B)
            none(TagTestEnum.C)
        }

        val entity = entity {
            it += TagTestComponent
        }

        assertEquals(0, enumFamily.numEntities)
        assertNotNull(entity[TagTestComponent])

        entity.configure { it -= TagTestComponent }

        assertEquals(0, enumFamily.numEntities)
        assertFalse(entity.contains(TagTestComponent))

        entity.configure { it += TagTestEnum.A }
        assertEquals(0, enumFamily.numEntities)

        entity.configure { it += TagTestEnum.B }
        assertEquals(1, enumFamily.numEntities)

        entity.configure { it += TagTestEnum.B }
        assertEquals(1, enumFamily.numEntities)

        assertEquals(entity, enumFamily.first())

        entity.configure { it += TagTestEnum.C }
        assertEquals(0, enumFamily.numEntities)
    }

}
