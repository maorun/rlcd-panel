package com.rlcdpanel.phone.protocol

/** Console command of the salt check; N is 0 (onboard) to 10. */
object SaltCommand {
    fun line(sensor: Int, magnesium: Boolean): String? =
        if (sensor !in 0..10) null else if (magnesium) "SALT $sensor MGCL2" else "SALT $sensor"
}
