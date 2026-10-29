package br.com.reboot.poc.infrastructure.vpn

class VpnResourceScope : AutoCloseable {

    private val lock = Any()
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false

    fun <T : AutoCloseable> register(resource: T): T {
        synchronized(lock) {
            if (closed) {
                try {
                    resource.close()
                } catch (_: Exception) {
                }

                throw IllegalStateException(
                    "VPN resource scope already closed"
                )
            }

            resources.add(resource)
        }

        return resource
    }

    fun isClosed(): Boolean =
        synchronized(lock) { closed }

    override fun close() {
        val pending: List<AutoCloseable>

        synchronized(lock) {
            if (closed) return

            closed = true
            pending = resources.asReversed().toList()
            resources.clear()
        }

        var firstError: Exception? = null

        pending.forEach { resource ->
            try {
                resource.close()
            } catch (ex: Exception) {
                if (firstError == null) {
                    firstError = ex
                } else {
                    firstError?.addSuppressed(ex)
                }
            }
        }

        if (firstError != null) {
            throw firstError!!
        }
    }
}
