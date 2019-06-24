package qbit.model

import qbit.*
import qbit.ns.Key
import qbit.ns.Namespace

// Interface


sealed class Attr<out T : Any> : Entitiable<EID?> {

    abstract val name: Key

    abstract val type: DataType<T>

    abstract val unique: Boolean

    fun isList() = type.isList()

    fun str() = name.toStr()

}

sealed class ValAttr<out T : Any> : Attr<T>()

sealed class ScalarAttr<out T : Any> : ValAttr<T>()

sealed class ListAttr<out T : Any> : ValAttr<List<T>>()

sealed class RefAttr<out T : Any> : Attr<T>()

sealed class ScalarRefAttr : RefAttr<Entitiable<EID?>>()

sealed class RefListAttr : RefAttr<List<Entitiable<EID?>>>()


fun <T : Any> ScalarAttr(name: Key, type: DataType<T>, unique: Boolean = false): ScalarAttr<T> = ScalarAttrImpl(name, type, unique)

fun RefAttr(name: Key, unique: Boolean = false): ScalarRefAttr = ScalarRefAttrImpl(name, QEntity, unique)

fun <T : Any> ListAttr(name: Key, type: DataType<T>, unique: Boolean = false): ListAttr<T> = ListAttrImpl(name, type, unique)

fun RefListAttr(name: Key, unique: Boolean = false): RefListAttr = RefListAttrImpl(name, unique)

// Utilities


@Suppress("UNCHECKED_CAST")
infix fun <T : Any> Attr<T>.eq(v: T): AttrValue<Attr<T>, T> = when (this) {
    is ScalarAttr -> this eq v
    is ScalarRefAttr -> (this eq (v as Entitiable<*>)) as AttrValue<Attr<T>, T>
    is ListAttr<*> -> (this eq (v as List<T>)) as AttrValue<Attr<T>, T>
    is RefListAttr -> (this eq (v as List<Entitiable<*>>)) as AttrValue<Attr<T>, T>
}

infix fun <T : Any> ScalarAttr<T>.eq(v: T): ScalarAttrValue<T> = ScalarAttrValue(this, v)

infix fun <T : Any> ListAttr<T>.eq(v: List<T>): AttrValue<Attr<List<T>>, List<T>> = ListAttrValue(this, v)

infix fun ScalarRefAttr.eq(v: Entitiable<*>): ScalarRefAttrValue = ScalarRefAttrValue(this, v)

infix fun RefListAttr.eq(v: List<Entitiable<*>>): RefListAttrValue = RefListAttrValue(this, v)


// Implementation


internal fun ListAttr(name: String, type: DataType<*>, unique: Boolean = false): Attr<*> = ListAttr(Key(name), type, unique)

internal fun Attr(name: String, type: DataType<*>, unique: Boolean = false): Attr<*> = ScalarAttr(Key(name), type, unique)

internal fun RefAttr(name: String, unique: Boolean = false): Attr<*> = RefAttr(Key(name), unique)

internal fun RefListAttr(name: String, unique: Boolean = false): Attr<*> = RefListAttr(Key(name), unique)

private data class ScalarAttrImpl<T : Any>(override val name: Key, override val type: DataType<T>, override val unique: Boolean = false) : ScalarAttr<T>(), Entitiable<EID?> by AttrEntityImpl(name, type, unique)

private data class ScalarRefAttrImpl(override val name: Key, override val type: DataType<Entity<*>>, override val unique: Boolean = false) : ScalarRefAttr(), Entitiable<EID?> by AttrEntityImpl(name, type, unique)

// TODO: what is unique means for lists?
private data class ListAttrImpl<T : Any>(override val name: Key, val itemsType: DataType<T>,
                                         override val unique: Boolean = false)
    :
        ListAttr<T>(), Entitiable<EID?> by AttrEntityImpl(name, itemsType, unique, true) {

    override val type: DataType<List<T>> = this.itemsType.list()

}

private data class RefListAttrImpl(override val name: Key, override val unique: Boolean = false)
    :
        RefListAttr(), Entitiable<EID?> by AttrEntityImpl(name, QEntity, unique, true) {

    override val type: DataType<List<Entity<*>>> = QEntity.list()

}

private data class AttrEntityImpl(val name: Key, val type: DataType<*>, val unique: Boolean = false, val list: Boolean = false) : Entitiable<EID?>{

    override val eid: EID? = null

    private val map = mapOf(EAttr.name to name.toStr(), EAttr.type to type.code, EAttr.unique to unique, EAttr.list to list)

    override val keys: Set<ScalarAttr<Any>> by lazy {  map.keys.toSet() }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> tryGet(key: Attr<T>): T? = (map as Map<Attr<T>, T>)[key]

}

private const val nsSep = "."
private const val keySep = "/"

private fun Key.toStr() = this.ns.parts.joinToString(nsSep) + keySep + this.name

private fun Key(keyStr: String): Key {
    val parts = keyStr.split(keySep)
    if (parts.size != 2) {
        throw IllegalArgumentException("Malformed attribute name: $keyStr")
    }
    val (ns, name) = parts
    return Namespace.of(*ns.split(nsSep).toTypedArray())[name]
}