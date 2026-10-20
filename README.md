# Fone Control

App Android para controlar fones Bluetooth:

- **Bateria** de cada lado e do estojo (quando o fone informa)
- **Volume separado** para esquerdo e direito
- **Troca de microfone** entre o do fone e o do celular

Requisitos: Android 8 ou mais novo (volume por lado precisa de Android 10+).

## Suporte por marca

| Fone | Bateria por lado | Como funciona |
|---|---|---|
| Samsung Galaxy Buds (todos) | Sim | Protocolo da Samsung via RFCOMM |
| Xiaomi / Redmi com Google Fast Pair | Sim | Anúncio BLE do Fast Pair |
| Xiaomi / Redmi sem Fast Pair | Só geral | Log bruto no Diagnóstico para mapear depois |
| Outros (Kaidi, genéricos…) | Só geral | Valor que o Android já conhece |

Volume por lado e troca de microfone funcionam com **qualquer** fone.

## Liberar o volume por lado (uma vez)

O Android só deixa apps mudarem o balanço esquerda/direita com uma permissão especial.
Com o celular ligado no computador (depuração USB ativa):

```
adb shell pm grant com.fonecontrol.app android.permission.WRITE_SECURE_SETTINGS
```

Sem computador, dá para rodar o mesmo comando pelo app **LADB** ou **Shizuku** (depuração sem fio).

## Gerar o APK

O GitHub Actions compila sozinho a cada push na branch `main`.
O APK aparece em **Releases** do repositório (e em Actions → Artifacts).

Para compilar no computador: abra a pasta no Android Studio, ou rode `./gradlew assembleDebug`.

## Estrutura

```
bt/HeadsetDriver.kt      interface dos drivers + escolha por nome do fone
bt/GenericDriver.kt      bateria geral + Fast Pair (qualquer fone)
bt/SamsungDriver.kt      Galaxy Buds (RFCOMM)
bt/XiaomiDriver.kt       Xiaomi/Redmi (Fast Pair + captura de dados brutos)
bt/BleBatteryScanner.kt  leitura de anúncios BLE
audio/BalanceController  volume por lado (volume de mídia + balanço do sistema)
audio/MicRouter          escolha do microfone
audio/MicRoutingService  mantém o mic do fone ativo em segundo plano
ui/                      telas em Jetpack Compose
```

Para adicionar uma marca nova: crie uma classe que implemente `HeadsetDriver`
e registre em `DriverFactory`.
