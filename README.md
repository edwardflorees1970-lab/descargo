# Descargo — descargador de video y audio para Android

Pega o comparte un enlace de YouTube, TikTok, Facebook, Pinterest (y unos 1000 sitios más que soporta yt-dlp) y elige formato y calidad.

## Qué hace

| Pestaña | Opciones |
|---|---|
| **Video** | "Mejor calidad" automática y luego una opción por resolución (4K/2K/1080p60/720p/…) con codec y tamaño estimado. Une video y mejor audio en un MP4. Hasta 1080p prefiere H.264 (se reproduce en cualquier equipo); por encima usa VP9/AV1, que es lo único que existe a esas resoluciones. |
| **MP3** | 320 / 256 / 192 / 160 / 128 / 96 kbps, con portada y metadatos. Avisa cuando el bitrate pedido supera al de la fuente, porque en ese caso no mejora el sonido. |
| **FLAC** | Sin pérdida, a partir de la mejor pista o de la pista que elijas (por ejemplo AAC 5.1 388 kbps u Opus 160 kbps). |
| **Audio original** | La pista tal cual la entrega el sitio (Opus/AAC), sin recodificar. Es la máxima calidad real. |

La app tiene cuatro pestañas:
- **Inicio:** pegas o compartes un enlace (**Compartir → Descargo** desde otras apps). Detecta si copiaste un enlace al abrir la app. Desde aquí ves las descargas en curso y puedes cancelar una o todas.
- **Playlists y canales:** al pegar una lista o un canal de YouTube (o un perfil de TikTok) aparecen todos los videos, hasta 500. Marcas cuáles quieres, eliges un formato para todos (video ≤1080p/720p…, MP3, FLAC, original) y se ponen en cola. En los lotes se espacian las peticiones para no activar el anti-bot de YouTube.
- **Navegador:** navegas YouTube, TikTok, Facebook, Instagram, Pinterest o X dentro de la app. En una página de video aparece el botón flotante **Descargar**.
- **Biblioteca:** historial permanente con miniaturas, búsqueda y filtros (Videos, Audio, Fallidas). Puedes abrir, compartir o borrar el archivo, y **Reintentar** las descargas fallidas.
- **Ajustes:**
  - Calidad de video y bitrate MP3 predeterminados, y la pestaña que se abre al analizar.
  - Descargas simultáneas (de 1 a 5) y opción "solo Wi-Fi".
  - Subtítulos incrustados (idiomas configurables y automáticos opcionales).
  - **Usar mi sesión:** inicias sesión en el Navegador y yt-dlp usa esas cookies para videos privados o con restricción de edad.
  - Canal nightly del motor.

Novedades de la 1.3:
- **Carpeta a elección:** la primera vez la app pregunta dónde guardar. Dentro de la carpeta elegida crea **Audio** (MP3, FLAC, audio original) y **Video**, y cada descarga va sola a la que corresponde. Al elegir carpeta ofrece mover y ordenar las descargas anteriores. Se cambia en Ajustes → Archivos.

Novedades de la 1.2:
- **Recortar:** baja solo un fragmento (de 1:05 a 2:30, por ejemplo) de un video o audio, con corte exacto.
- **YouTube:** quitar patrocinios con SponsorBlock (opcional), guardar capítulos y separar «Artista - Canción» en las etiquetas del audio.
- **Editor de etiquetas** en la Biblioteca: título, artista, álbum y portada de MP3, FLAC y M4A, sin recodificar.
- **Pausar y reanudar** descargas: continúan desde donde quedaron. También desde la notificación, y con «Pausar todo» o «Reanudar todo».
- **Límite de velocidad:** 500 KB/s, 1, 2 o 5 MB/s.
- **Suscripciones:** desde una lista o canal tocas «Suscribirse» y los videos nuevos se descargan solos con el formato elegido, revisando cada 1 a 24 h.
- **Apariencia:** tema claro, oscuro o igual al sistema, y colores de tu fondo de pantalla (Material You, Android 12 o más nuevo).
- **Archivos:** puedes guardar todo en Descargas o separar el audio en Music y el video en Movies. También eliges el nombre de la carpeta, una subcarpeta por sitio y la plantilla del nombre de archivo.
- **Atajo «Descargar copiado»** en Ajustes rápidos: abre la app y analiza el enlace que tengas copiado.

Además:
- Las descargas siguen en segundo plano, con notificación y botón para cancelar. Los archivos se guardan en `Descargas/Descargo`.
- yt-dlp se actualiza solo cada 12 h; también hay un botón **Actualizar**.
- Reintenta solo cuando hay cortes de red, SSL o DNS y ante el control anti-bot de YouTube.

> **Sobre FLAC:** YouTube, TikTok y los demás sitios entregan audio comprimido. FLAC lo guarda sin pérdidas, pero no puede sonar mejor que la fuente. Si buscas la mejor calidad real, usa **Audio original**.

## Instalar en el celular

1. Copia `dist/Descargo-arm64.apk` al celular. Casi todos los teléfonos desde 2017 son arm64; para los muy viejos usa `Descargo-arm32.apk`.
2. Ábrelo y permite "instalar apps de origen desconocido".
3. La primera vez que se abre, la app tarda un poco en preparar el motor (descomprime Python, yt-dlp y ffmpeg).

## Compilar

Requiere Android Studio (usa su JDK 21) y el SDK 36.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
```

Los APK quedan en `app/build/outputs/apk/release/`: uno por ABI y uno universal, que pesa unos 200 MB. Este proyecto también se puede abrir directamente en Android Studio.

## Estructura

```
app/src/main/java/com/descargo/app/
  DescargoApp.kt          inicia el motor y los canales de notificación
  engine/Engine.kt        yt-dlp: inicio, actualización, análisis, reintentos y mensajes de error
  engine/FormatPlanner.kt convierte los formatos de yt-dlp en las opciones Video/MP3/FLAC/Original
  work/DownloadWorker.kt  descarga en segundo plano (WorkManager) con progreso
  work/Storage.kt         copia el archivo a Descargas/Descargo (MediaStore)
  ui/                     pantalla en Jetpack Compose
```

Motor: [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1, que incluye yt-dlp, Python 3.12, ffmpeg y QuickJS para los retos JS de YouTube. Es software GPL-3.0.

## Limitaciones conocidas

- Videos privados, con restricción de edad o que piden iniciar sesión no se pueden descargar, porque la app no usa cookies.
- En Facebook, algunos videos solo exponen una calidad ("Original").
- Descargar contenido de YouTube y otras plataformas puede violar sus términos de servicio. Úsalo para contenido propio, con licencia libre o para uso personal donde la ley lo permita.
- Por eso esta app no puede publicarse en Google Play. Se distribuye como APK.
