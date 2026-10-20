package com.fonecontrol.app.bt

/** Nível de bateria de uma parte do fone (0..100). */
data class SideBattery(val level: Int, val charging: Boolean = false)

/**
 * Estado de bateria conhecido do fone. Qualquer campo pode ser nulo quando
 * o fone (ou o driver) não informa aquele valor.
 */
data class BatteryInfo(
    val left: SideBattery? = null,
    val right: SideBattery? = null,
    val case: SideBattery? = null,
    /** Valor único que o Android expõe para qualquer fone (média ou o lado principal). */
    val overall: Int? = null,
    /** De onde veio a informação, para mostrar na tela. */
    val source: String = "",
) {
    val hasSides: Boolean get() = left != null || right != null
}
