package com.altinvolkan.imirror.tv

/**
 * UDP parçalarını kareye birleştirir (tvOS FrameReassembler'ın birebir karşılığı). Gecikme için kayıp
 * karelerde beklemez: boşluk görürse [needKeyframe] işaretler, sonraki keyframe'e kadar P-kareleri atar.
 */
class FrameReassembler {
    private class Partial(val parts: Array<ByteArray?>, var received: Int = 0, var key: Boolean)

    private val pending = HashMap<Int, Partial>()
    private var lastDelivered = 0
    private var hasLast = false

    var needKeyframe = true
        private set

    private fun isNewer(a: Int, b: Int) = (a - b) > 0 // 32 bit sarma güvenli

    fun reset() {
        pending.clear()
        hasLast = false
        needKeyframe = true
    }

    /** Kare tamamlandıysa (ve çözülebilirse) Annex-B access unit'i döner. */
    fun push(frameId: Int, index: Int, count: Int, key: Boolean, payload: ByteArray): ByteArray? {
        if (count <= 0 || index >= count) return null
        if (hasLast && !isNewer(frameId, lastDelivered)) return null // eski/yinelenen

        val p = pending.getOrPut(frameId) { Partial(arrayOfNulls(count), 0, key) }
        if (p.parts.size != count) return null
        if (p.parts[index] == null) {
            p.parts[index] = payload
            p.received++
        }
        if (key) p.key = true

        if (p.received != count) {
            if (pending.size > 6) {
                val oldest = pending.keys.reduce { a, b -> if (isNewer(a, b)) b else a }
                pending.remove(oldest)
                needKeyframe = true
            }
            return null
        }

        // Kare tamam: daha eski bekleyenleri at
        pending.remove(frameId)
        val it = pending.keys.iterator()
        while (it.hasNext()) if (isNewer(frameId, it.next())) it.remove()

        val contiguous = hasLast && frameId == lastDelivered + 1
        lastDelivered = frameId
        hasLast = true

        var total = 0
        for (part in p.parts) total += part!!.size
        val frame = ByteArray(total)
        var off = 0
        for (part in p.parts) {
            System.arraycopy(part!!, 0, frame, off, part.size)
            off += part.size
        }

        if (p.key) {
            needKeyframe = false
            return frame
        }
        if (contiguous && !needKeyframe) return frame
        needKeyframe = true
        return null
    }
}
