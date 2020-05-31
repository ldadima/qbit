package qbit.serialization

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import qbit.api.QBitException
import qbit.api.model.Hash
import qbit.ns.Namespace
import qbit.platform.MessageDigests
import qbit.platform.asInput
import qbit.platform.createSingleThreadCoroutineDispatcher
import qbit.spi.Storage

private val nodes = Namespace("nodes")

class JvmNodesStorage(private val storage: Storage) :
        (NodeRef) -> NodeVal<Hash>?,
    CoroutineScope by CoroutineScope(createSingleThreadCoroutineDispatcher("Nodes writer")),
    NodesStorage {

    override suspend fun store(n: NodeVal<Hash?>): NodeVal<Hash> {
        return withContext(this.coroutineContext) {
            val data = SimpleSerialization.serializeNode(n)
            val hash = hash(data)
            if (n.hash != null && n.hash != hash) {
                throw AssertionError("NodeVal has hash ${n.hash!!.toHexString()}, but it's serialization has hash ${hash.toHexString()}")
            }
            if (!storage.hasKey(hash.key())) {
                storage.add(hash.key(), data)
            }
            toHashedNode(n, hash)
        }
    }

    override fun invoke(ref: NodeRef): NodeVal<Hash>? {
        try {
            val value = storage.load(ref.key()) ?: return null
            val hash = hash(value)
            if (hash != ref.hash) {
                throw QBitException("Corrupted node. Node hash is ${ref.hash}, but data hash is $hash")
            }
            return toHashedNode(SimpleSerialization.deserializeNode(value.asInput()), hash)
        } catch (e: Exception) {
            throw QBitException(cause = e)
        }
    }

    override fun load(n: NodeRef): NodeVal<Hash>? {
        return invoke(n)
    }

    private fun Node<Hash>.key() = nodes[hash.toHexString()]

    private fun Hash.key() = nodes[toHexString()]

    private fun toHashedNode(n: NodeVal<Hash?>, hash: Hash): NodeVal<Hash> = when (n) {
        is Root -> Root(hash, n.source, n.timestamp, n.data)
        is Leaf -> Leaf(hash, n.parent, n.source, n.timestamp, n.data)
        is Merge -> Merge(hash, n.parent1, n.parent2, n.source, n.timestamp, n.data)
    }

    override fun hasNode(head: Node<Hash>): Boolean =
            storage.hasKey(head.key())

}

fun hash(data: ByteArray): Hash =
    Hash(MessageDigests.getInstance("SHA-1").digest(data))
