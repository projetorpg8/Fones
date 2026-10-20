package com.fonecontrol.app.bt

import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * Xiaomi / Redmi: a maioria dos modelos recentes suporta Google Fast Pair, que já traz
 * bateria por lado (lida pelo [GenericDriver]). Para os que não suportam, o driver
 * grava no Diagnóstico os anúncios brutos da Xiaomi (ID de fabricante 0x038F), para
 * que o formato do seu modelo possa ser mapeado depois.
 */
class XiaomiDriver(context: Context, device: BluetoothDevice) :
    GenericDriver(
        context,
        device,
        label = "Xiaomi / Redmi (experimental)",
        useBle = true,
        captureManufacturerId = XIAOMI_MANUFACTURER_ID,
    ) {

    companion object {
        const val XIAOMI_MANUFACTURER_ID = 0x038F
    }
}
