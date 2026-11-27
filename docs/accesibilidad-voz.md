# Modo accesible por voz - Omnibus Paysandu

## Objetivo
Permitir que personas no videntes usen la app por voz: el sistema habla (TTS) y escucha (SpeechRecognizer), y guia hasta la parada.

## Comandos de voz (MVP)
1. "Lineas" -> lista las lineas disponibles (101-110 y especiales).
2. "Linea 103" -> informa recorrido, sentido, coches en calle y atraso.
3. "Ubicacion" -> dice donde esta la persona ahora, usando puntos de referencia cercanos del JSON (NAMES/DEST), ej. "Estas a 80 m de Colon y Limite, cerca de la sede del Litoral".
4. "Linea 103 subir" -> busca la parada mas cercana de esa linea, guia por voz hasta llegar, avisa al llegar y da tiempo probable de llegada del bus.
5. "Bajar" -> (pendiente) dictar parada destino.

## Flujo de voz
- Activar modo accesible -> TTS saluda y lista comandos.
- SpeechRecognizer en espanol (es-UY/es-ES) con resultados parciales.
- Parser normaliza texto: minusculas, quita tildes, convierte numeros ("ciento tres" -> 103).
- Si no se entiende, TTS pide repetir.

## Geolocalizacion por referencia
- Usar Pt del usuario y calcular distancia a cada entry de NAMES/DEST.
- Tomar los 3 mas cercanos y anunciar el mas cercano con distancia y direccion cardinal.

## Guia a parada ("subir")
- Filtrar subs de la linea, elegir parada mas cercana a la ubicacion.
- Anunciar nombre y direccion aproximada.
- Polling cada 15s: cuando distancia < 30 m, anunciar "Llegaste a la parada".
- Usar EtaEngine.arrivalsAt(parada) para anunciar tiempo probable.

## Permisos
- RECORD_AUDIO, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS.

## Pendientes
- Comando "bajar" y parada destino.
- Vibracion y sonidos de confirmacion.
- Modo offline de puntos de referencia.