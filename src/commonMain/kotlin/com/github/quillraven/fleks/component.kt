package com.github.quillraven.fleks

import com.github.quillraven.fleks.collection.Bag
import com.github.quillraven.fleks.collection.BitArray
import com.github.quillraven.fleks.collection.bag
import kotlin.math.max
import kotlin.native.concurrent.ThreadLocal

interface Tracker<T> {
    val size: Int

    operator fun get(i: Int): T?
    fun getOrNull(i: Int) : T?
    operator fun set(i: Int, el: T?)

    fun resize(size: Int)
}

class ComponentTracker<T>(capacity: Int) : Tracker<T> {

    private var components: Array<T?> = Array<Any?>(capacity) { null } as Array<T?>

    override val size: Int
        get() = components.size

    override operator fun get(i: Int) = components[i]

    override fun getOrNull(i: Int): T? {
        if (i !in components.indices) return null
        return components[i]
    }

    override fun set(i: Int, el: T?) {
        components[i] = el
    }

    override fun resize(size: Int) {
        components = components.copyOf(size)
    }
}

class TagTracker<T>(capacity: Int) : Tracker<T> {

    private val mask: BitArray = BitArray(capacity)
    private var value: T? = null

    override val size: Int
        = mask.capacity

    override fun get(i: Int): T? = value?.takeIf { mask[i] }

    override fun getOrNull(i: Int): T? = value?.takeIf { i in 0..<mask.length() && mask[i] }

    override fun resize(size: Int) {
        if (size > mask.capacity) {
            mask.set(size - 1)
            mask.clear(size - 1)
        }
    }

    override fun set(i: Int, el: T?) {
        if (el == null) {
            mask.clear(i)
        } else {
            value ?:let { value = el }
            mask.set(i)
        }
    }

}

interface TrackableType<T> {
    val id: Int
    fun getTracker(capacity: Int): Tracker<T>
}


abstract class DefaultTrackableType<T> : TrackableType<T> {
    override val id: Int = nextId++

    @ThreadLocal
    companion object {
        private var nextId = 0
    }
}

/**
 * A class that assigns a unique [id] per type of [Component] starting from 0.
 * This [id] is used internally by Fleks as an index for some arrays.
 * Every [Component] class must have at least one [ComponentType].
 */
abstract class ComponentType<T> : DefaultTrackableType<T>() {
    override fun getTracker(capacity: Int) = ComponentTracker<T>(capacity)
}

abstract class TagType<T> : DefaultTrackableType<T>() {
    override fun getTracker(capacity: Int) = TagTracker<T>(capacity)
}


interface TrackableComponentType<T> : TrackableType<T>, Component<T>

open class TagComponent<T>: TrackableComponentType<T>, TagType<T>() {
    override fun type(): TrackableType<T> {
        return this
    }
}

/**
 * Function to create an object for a [ComponentType] of type T.
 * This is a convenience function for [components][Component] that have more than one [ComponentType].
 */
inline fun <reified T> componentTypeOf(): ComponentType<T> = object : ComponentType<T>() {}

/**
 * An interface that must be implemented by any component that is used for Fleks.
 * A component must have at least one [ComponentType] that is provided via the [type] function.
 *
 * One convenient approach is to use the unnamed companion object of a Kotlin class as a [ComponentType].
 * Sample code for a component that stores the position of an entity:
 *
 *     data class Position(
 *         var x: Float,
 *         var y: Float,
 *     ) : Component<Position> {
 *         override fun type(): ComponentType<Position> = Position
 *
 *         companion object : ComponentType<Position>()
 *     }
 */
interface Component<T> {
    /**
     * Returns the [TrackableType] of a [Component].
     */
    fun type(): TrackableType<T>

    /**
     * Lifecycle method that gets called whenever a [component][Component] gets set for an [entity][Entity].
     */
    fun World.onAdd(entity: Entity) = Unit

    /**
     * Lifecycle method that gets called whenever a [component][Component] gets removed from an [entity][Entity].
     */
    fun World.onRemove(entity: Entity) = Unit
}

/**
 * A class that is responsible to store components of a specific type for all [entities][Entity] in a [world][World].
 * The index of the [components] array is linked to the id of an [entity][Entity]. If an [entity][Entity] has
 * a component of this specific type then the value at index 'entity.id' is not null.
 *
 * Refer to [ComponentService] for more details.
 */
class ComponentsHolder<T : Component<*>>(
    private val world: World,
    private val type: TrackableType<*>,
    private var tracker: Tracker<T>,
) {
    /**
     * Sets the [component] for the given [entity]. This function is only
     * used by [World.loadSnapshot] where we don't have the correct type information
     * during runtime, and therefore we can only provide 'Any' as a type and need to cast it internally.
     */
    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun setWildcard(entity: Entity, component: Any) = set(entity, component as T)

    /**
     * Sets the [component] for the given [entity].
     * If the [entity] already had a component, the [onRemove][Component.onRemove] lifecycle method
     * will be called.
     * After the [component] is assigned to the [entity], the [onAdd][Component.onAdd] lifecycle method
     * will be called.
     */
    operator fun set(entity: Entity, component: T) {
        if (entity.id >= tracker.size) {
            // not enough space to store the new component -> resize array
            tracker.resize(max(tracker.size * 2, entity.id + 1))
        }

        // check if the remove lifecycle method of the previous component needs to be called
        tracker[entity.id]?.run {
            // assign current component to null in order for 'contains' calls inside the lifecycle
            // method to correctly return false
            tracker[entity.id] = null
            world.onRemove(entity)
        }

        // set component and call lifecycle method
        tracker[entity.id] = component
        component.run { world.onAdd(entity) }
    }

    /**
     * Removes a component of the specific type from the given [entity].
     * If the entity has such a component, its [onRemove][Component.onRemove] lifecycle method will
     * be called.
     *
     * @throws [IndexOutOfBoundsException] if the id of the [entity] exceeds the components' capacity.
     */
    operator fun minusAssign(entity: Entity) {
        if (entity.id < 0 || entity.id >= tracker.size) throw IndexOutOfBoundsException("$entity.id is not valid for components of size ${tracker.size}")

        val existingCmp = tracker[entity.id]
        // assign null before running the lifecycle method in order for 'contains' calls to correctly return false
        tracker[entity.id] = null
        existingCmp?.run { world.onRemove(entity) }
    }

    /**
     * Returns a component of the specific type of the given [entity].
     *
     * @throws [FleksNoSuchEntityComponentException] if the [entity] does not have such a component.
     */
    operator fun get(entity: Entity): T {
        return tracker[entity.id] ?: throw FleksNoSuchEntityComponentException(entity, componentName())
    }

    /**
     * Returns a component of the specific type of the given [entity] or null if the entity does not have such a component.
     */
    fun getOrNull(entity: Entity): T? =
        tracker.getOrNull(entity.id)

    /**
     * Returns true if and only if the given [entity] has a component of the specific type.
     */
    operator fun contains(entity: Entity): Boolean =
        tracker.size > entity.id && tracker[entity.id] != null

    /**
     * Returns the simplified component name of a [ComponentType].
     * The default toString() format is 'package.Component$Companion'.
     * This method returns 'Component' without package and companion.
     */
    private fun componentName(): String = type::class.toString().substringAfterLast(".").substringBefore("$")

    override fun toString(): String {
        return "ComponentsHolder(type=${componentName()}, id=${type.id})"
    }
}

/**
 * A service class that is responsible for managing [ComponentsHolder] instances.
 * It creates a [ComponentsHolder] for every unique [ComponentType].
 */
class ComponentService {
    @PublishedApi
    internal lateinit var world: World

    /**
     * Returns [Bag] of [ComponentsHolder].
     */
    @PublishedApi
    internal val holdersBag = bag<ComponentsHolder<*>>()

    /**
     * Returns a [ComponentsHolder] for the given [componentType]. This function is only
     * used by [World.loadSnapshot] where we don't have the correct type information
     * during runtime, and therefore we can only provide '*' as a type and need to cast it internally.
     */
    fun wildcardHolder(componentType: TrackableType<*>): ComponentsHolder<*> {
        if (holdersBag.hasNoValueAtIndex(componentType.id)) {
            holdersBag[componentType.id] =
                ComponentsHolder(world, componentType, componentType.getTracker(world.capacity) as Tracker<Component<*>>)
        }
        return holdersBag[componentType.id]
    }

    /**
     * Returns a [ComponentsHolder] for the given [componentType]. If no such holder exists yet, then it
     * will be created and added to the [holdersBag].
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Component<*>> holder(componentType: TrackableType<T>): ComponentsHolder<T> {
        if (holdersBag.hasNoValueAtIndex(componentType.id)) {
            holdersBag[componentType.id] = ComponentsHolder(world, componentType, componentType.getTracker(world.capacity))
        }
        return holdersBag[componentType.id] as ComponentsHolder<T>
    }

    /**
     * Returns the [ComponentsHolder] of the given [index] inside the [holdersBag]. The index
     * is linked to the id of a [ComponentType].
     * This function is only used internally at safe areas to speed up certain processes like
     * removing an [entity][Entity] or creating a snapshot via [World.snapshot].
     *
     * @throws [IndexOutOfBoundsException] if the [index] exceeds the bag's capacity.
     */
    internal fun holderByIndex(index: Int): ComponentsHolder<*> {
        return holdersBag[index]
    }
}
