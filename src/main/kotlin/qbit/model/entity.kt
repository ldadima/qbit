@file:Suppress("UNCHECKED_CAST")

package qbit.model

import qbit.Db
import qbit.Fact
import qbit.tombstone
import java.util.*
import java.util.Collections.singleton
import java.util.Collections.singletonList

interface AttrValue<out A : Attr<T>, out T : Any> {

    val attr: A
    val value: T

    fun toPair() = attr to value

    operator fun component1(): Attr<T> = attr

    operator fun component2(): T = value

}

data class ScalarAttrValue<T : Any>(override val attr: Attr<T>, override val value: T) : AttrValue<Attr<T>, T>
data class ScalarRefAttrValue(override val attr: ScalarRefAttr, override val value: Entitiable<*>) : AttrValue<ScalarRefAttr, Entitiable<*>>
data class ListAttrValue<T : Any>(override val attr: ListAttr<T>, override val value: List<T>) : AttrValue<ListAttr<T>, List<T>>
data class RefListAttrValue(override val attr: RefListAttr, override val value: List<Entitiable<*>>) : AttrValue<RefListAttr, List<Entitiable<*>>>

fun Entity(vararg entries: AttrValue<Attr<*>, *>): MutableEntitiable<EID?> {
    return DetachedEntity(null, entries.map { it.toPair() }.toMap())
}

internal fun Entity(eid: EID, entries: Collection<Pair<Attr<*>, Any>>, db: Db): AttachedEntity = AttachedEntity(eid, entries.toMap(), db, false)

// TODO: make it lazy
internal fun Entity(eid: EID, db: Db): AttachedEntity = db.pull(eid)!!

interface Entitiable<out E : EID?> {

    val eid: E

    val keys: Set<Attr<Any>>

    operator fun <T : Any> get(key: Attr<T>): T = tryGet(key)!!

    fun <T : Any> tryGet(key: Attr<T>): T?

    val entries: Set<AttrValue<Attr<Any>, Any>>
        get() = keys.map {
            it eq this[it]
        }.toSet()

}

fun Entitiable<EID?>.toIdentified(eid: EID): MutableEntity<EID> {
    return DetachedEntity(eid, this)
}

interface MutableEntitiable<out E : EID?> : Entitiable<E> {

    fun <T : Any> with(key: Attr<T>, value: T): MutableEntitiable<E> =
            with(key eq value)

    fun with(vararg values: AttrValue<Attr<*>, *>): MutableEntitiable<E>

    fun <T : Any> remove(key: Attr<T>): MutableEntitiable<E>

}

sealed class Entity<out E : EID?>(override val eid: E) : Entitiable<E>

class Tombstone(eid: EID) : Entity<EID>(eid) {

    override val keys: Set<Attr<Any>>
        get() = setOf(tombstone)

    override fun <T : Any> tryGet(key: Attr<T>): T? =
            when (key) {
                tombstone -> true as T
                else -> null
            }

}

sealed class MutableEntity<out E : EID?>(eid: E) : Entity<E>(eid), MutableEntitiable<E>

class DetachedEntity<E : EID?>(eid: E, map: Map<Attr<*>, *>) : MutableEntity<E>(eid) {

    private val delegate = MapEntity(map) { newMap -> DetachedEntity<E>(eid, newMap) }

    constructor () : this(null as E, emptyMap<Attr<*>, Any>())

    constructor(eid: EID) : this(eid as E, emptyMap<Attr<*>, Any>())

    constructor(e: Entitiable<EID?>) : this(e.eid as E, e.entries.map { it.toPair() }.toMap())

    constructor(eid: EID, e: Entitiable<EID?>) : this(eid as E, e.entries.map { it.toPair() }.toMap())

    override val keys: Set<Attr<Any>>
        get() = delegate.keys

    override fun <T : Any> tryGet(key: Attr<T>): T? {
        return delegate.tryGet(key)
    }

    override fun <T : Any> with(key: Attr<T>, value: T): DetachedEntity<E> {
        return delegate.with(key, value)
    }

    override fun with(vararg values: AttrValue<Attr<*>, *>): DetachedEntity<E> {
        return delegate.with(*values)
    }

    override fun <T : Any> remove(key: Attr<T>): DetachedEntity<E> {
        return delegate.remove(key)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetachedEntity<*>) return false

        if (eid != other.eid) return false
        if (delegate != other.delegate) return false

        return true
    }

    override fun hashCode(): Int {
        var result = delegate.hashCode()
        result = 31 * result + eid.hashCode()
        return result
    }


}

class AttachedEntity(eid: EID, map: Map<Attr<*>, *>, val db: Db, val dirty: Boolean) : MutableEntity<EID>(eid) {

    private val delegate = MapEntity(map) { newMap -> AttachedEntity(eid, newMap, db, true) }

    override val keys: Set<Attr<Any>>
        get() = delegate.keys

    override fun <T : Any> tryGet(key: Attr<T>): T? {
        val value = delegate.tryGet(key)
        return if (key is RefAttr && value is EID) {
            db.pull(value) as T?
        } else if (key is RefListAttr && value is List<*> && value.size > 0 && value[0] is EID) {
            value.map { db.pull(it as EID) } as T?
        } else {
            value
        }
    }

    override fun <T : Any> with(key: Attr<T>, value: T): AttachedEntity {
        if (value == this.tryGet(key)) {
            return this
        }
        return delegate.with(key, value)
    }

    override fun with(vararg values: AttrValue<Attr<*>, *>): AttachedEntity {
        return delegate.with(*values)
    }

    override fun <T : Any> remove(key: Attr<T>): AttachedEntity {
        return delegate.remove(key)
    }

}

private class MapEntity<out T : MutableEntitiable<EID?>>(private val map: Map<Attr<*>, *>, private val create: (Map<Attr<*>, *>) -> T) : MutableEntitiable<EID?> {

    override val eid: EID? = null

    override val keys: Set<Attr<Any>>
        get() = map.keys

    override fun <T : Any> tryGet(key: Attr<T>): T? {
        return map[key] as T?
    }

    override fun <V : Any> with(key: Attr<V>, value: V): T =
            with(key eq value)

    override fun with(vararg values: AttrValue<Attr<*>, *>): T {
        val newMap = HashMap(map)
        for ((key, value) in values) {
            newMap[key] = value
        }
        return create(newMap)
    }

    override fun <V : Any> remove(key: Attr<V>): T {
        val newMap = HashMap(map)
        newMap -= key
        return create(newMap)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapEntity<*>) return false

        if (map.keys.size != other.keys.size) return false
        if (map.keys.any { map[it] != other.map[it] }) return false

        return true
    }

    override fun hashCode(): Int {
        return map.hashCode()
    }

}

internal fun unfoldEntitiesGraph(es: Collection<Entitiable<*>>, eids: Iterator<EID>): IdentityHashMap<Entitiable<*>, Entitiable<EID>> {
    val res = IdentityHashMap<Entitiable<*>, Entitiable<EID>>()

    fun body(es: Collection<Entitiable<*>>) {
        es.forEach {
            if (!res.contains(it)) {
                @Suppress("UNCHECKED_CAST")
                when  {
                    it.eid != null -> res[it] = it as Entitiable<EID>
                    else -> res[it] = it.toIdentified(eids.next())
                }
            }
            it.keys.forEach { attr ->
                if (attr is ScalarRefAttr) {
                    val value: Entitiable<*> = it[attr]
                    if (res[value] == null) {
                        body(singletonList(value))
                    }
                } else if (attr is RefListAttr) {
                    body(it[attr])
                }
            }
        }
    }
    body(es)

    return res
}

internal fun <T : Entitiable<EID>> T.setRefs(ref2eid: IdentityHashMap<Entitiable<*>, Entitiable<EID>>): DetachedEntity<EID> =
        this.entries
                .filter { it is ScalarRefAttrValue || (it is RefListAttrValue) }
                .fold(DetachedEntity(this)) { prev, av ->
                    when (av) {
                        is ScalarRefAttrValue -> {
                            prev.with(av.attr eq ref2eid[prev[av.attr]]!!)
                        }
                        else -> {
                            val entities: List<Entitiable<*>> = (av.value as List<Entitiable<*>>).map { ref2eid[it]!! }
                            prev.with(av.attr as RefListAttr, entities)
                        }
                    }
                }

internal fun Entitiable<EID?>.toFacts(eid: EID): Collection<Fact> =
        this.entries.flatMap { (attr: Attr<Any>, value) ->
            when (attr) {
                is ScalarRefAttr -> singleton(refToFacts(eid, attr, value))
                is ListAttr<*> -> listToFacts(eid, attr, value as List<Any>)
                is RefListAttr -> refListToFacts(eid, attr, value as List<Any>)
                else -> singleton(attrToFacts(eid, attr, value))
            }
        }

internal fun Entitiable<EID>.toFacts() =
        this.toFacts(eid)

private fun <T : Any> attrToFacts(eid: EID, attr: Attr<T>, value: T) =
        Fact(eid, attr, value)

private fun refToFacts(eid: EID, attr: ScalarRefAttr, value: Any) =
        Fact(eid, attr, eidOf(value)!!)

private fun listToFacts(eid: EID, attr: ListAttr<*>, value: List<Any>) =
        value.map { Fact(eid, attr, it) }

private fun refListToFacts(eid: EID, attr: RefListAttr, value: List<Any>) =
        value.map { Fact(eid, attr, eidOf(it)!!) }

private fun eidOf(a: Any): EID? =
        when {
            a is Entitiable<*> && a.eid != null -> a.eid
            a is EID -> a
            else -> null
        }