# Ómnibus Paysandú (Android)

App nativa (Kotlin + Jetpack Compose + osmdroid) con las funciones del **GPS urbano** de COPAY (`copay.com.uy/gps/`).

## Qué hace (MVP)
- Mapa en vivo de todos los coches (refresca cada 15 s). Cada coche tiene un núcleo con el color de su línea y una flecha que marca el rumbo. El color de la flecha indica la puntualidad: verde en hora, naranja atrasado, rojo muy atrasado, azul adelantado.
- **Paradas cerca de mí** (600 m): las 4 más cercanas, cada sublínea en su parada más próxima, con los minutos que faltan.
- **Búsqueda** de lugares (hospital, terminal, UTU…) y de paradas por calle; muestra las paradas cercanas al lugar elegido.
- **Líneas**: el recorrido dibujado en el mapa, el sentido, los coches en la calle con su atraso y la lista de paradas con su ETA.
- **Parada**: las próximas llegadas, en vivo o según horario, y la opción de marcarla como favorita.
- **Avisame cuando venga**: un servicio en primer plano consulta el GPS y te notifica cuando el coche está a 3, 5 o 10 min. Se corta solo a los 90 min.

## Origen de los datos
| Qué | Dónde |
|---|---|
| Posiciones en vivo | `https://bus.copay.com.uy:10443/pub/avl.xml` (XML ISO-8859-1, uno por `<marker>`) |
| Paradas, recorridos, horarios, patrones de tiempo, nombres, destinos | `app/src/main/assets/copay_data.json` (extraído del JS de la web el 27/11/2026) |

El motor de ETA (`domain/EtaEngine.kt`) es un port 1:1 del JS de la web. `EngineParityTest` lo compara contra un snapshot real: 17 llegadas y el resultado de "cerca de mí" calculados por la web en el mismo instante. Todo da idéntico.

> ⚠️ Los horarios son un snapshot. Si COPAY cambia horarios o recorridos, hay que regenerar `copay_data.json`.
> ⚠️ El feed y los datos son de COPAY. Antes de publicar en Play Store conviene pedir su autorización.

## Compilar
Requisitos: Android Studio (Ladybug o posterior) con JDK 17.

1. Abrir la carpeta `OmnibusPaysandu` en Android Studio y dejar que sincronice Gradle.
2. Para el APK de prueba: **Build → Build App Bundle(s)/APK(s) → Build APK(s)**. Sale en `app/build/outputs/apk/debug/app-debug.apk`.
   - Por consola: `gradlew assembleDebug`
3. Tests del motor: `gradlew testDebugUnitTest`

Para publicar hay que crear un keystore propio y configurar `signingConfig` en `app/build.gradle.kts`. Hoy el build `release` firma con la clave debug.

## Estructura
```
data/    StaticData (modelos + parse JSON), AvlFeed (descarga/parse XML), Repository, Prefs
domain/  EtaEngine (ETA, horarios, cercanía; port de la web)
ui/      MainViewModel (polling 15 s, navegación, búsqueda), MapPane (osmdroid), Panels (Compose)
service/ ArrivalAlertService (alerta en primer plano)
```

## Pendiente para próximas versiones
- Planificador "¿A dónde vas?" con transbordo en la punta de línea, como en la web.
- Modo "voy arriba del ómnibus" con aviso por voz.
- Horario completo del día por línea.
- Servir `copay_data.json` desde un endpoint propio para actualizar horarios sin tener que publicar una nueva versión.
- Tiles: para mucho tráfico, usar un proveedor propio. La política de uso de tile.openstreetmap.org no admite apps de alto volumen.

## Modo accesible (no videntes)

La app esta pensada tambien para personas no videntes.

- Interfaz negra dividida en dos: boton derecho (aceptar) y boton izquierdo (cancelar).
- Comandos de voz: "Listar", "Linea X", "Ubicacion", "Subir", "Bajar".
- Uso clasico del lenguaje oral; anuncio por voz (TTS) y escucha por voz (SpeechRecognizer).

## Uso clasico
- Mapa en vivo, paradas cerca de mi, busqueda, ETA, favoritos y alertas.