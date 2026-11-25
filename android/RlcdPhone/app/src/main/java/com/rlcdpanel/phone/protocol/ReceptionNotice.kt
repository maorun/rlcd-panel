package com.rlcdpanel.phone.protocol

/**
 * Plans the weak signal notifications of one `STATUS` read.
 *
 * [plan] feeds `LATCH rxlow` of the reply to a [ReceptionWatch] and gives one [Notice] for each slot whose bit rose
 * since the previous read. The first read, a reply without `rxlow` and an equal mask give no notice.
 */
class ReceptionNotice {

    /** One notification: [slot] (1 to 10), the [message] text and the [notificationId]. */
    data class Notice(val slot: Int, val message: String, val notificationId: Int)

    private val watch = ReceptionWatch()

    /**
     * [names] holds the tile names (index 0 onboard, 1 to 10 slots). A slot without a name (index outside the list
     * or a blank text) uses its slot number as the name.
     */
    fun plan(status: StatusReply, names: List<String?>): List<Notice> =
        watch.update(status.latch?.receptionLow).map { slot ->
            val name = names.getOrNull(slot)?.takeIf { it.isNotBlank() } ?: slot.toString()
            Notice(slot, ReceptionWatch.message(name), ReceptionWatch.notificationId(slot))
        }
}
