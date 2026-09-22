package cl.umag.glaciertemp.core.fieldbook

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escribe un documento OpenDocument Text a mano.
 *
 * Un `.odt` es un ZIP con `mimetype`, `META-INF/manifest.xml`, `content.xml`, `styles.xml` y
 * las imagenes en `Pictures/`. Escribirlo directamente evita meter una dependencia de ODF en
 * `:core`, que es lo que permite probar toda la exportacion en el escritorio en segundos -- y
 * lo que hace que el modulo siga compilando para Android sin arrastrar medio mundo.
 *
 * LA UNICA REGLA RARA DEL FORMATO: la entrada `mimetype` tiene que ser la PRIMERA del zip y
 * estar SIN COMPRIMIR, porque es lo que permite reconocer el tipo de fichero leyendo los
 * primeros bytes. Un zip por lo demas correcto que no cumpla eso lo rechazan LibreOffice y
 * Word sin decir por que.
 */
class OdtWriter {

    /** Una imagen ya reducida por quien llama: `core` no sabe decodificar JPEG. */
    data class Image(
        val name: String,
        val bytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
        val mediaType: String = "image/jpeg",
    )

    private val cuerpo = StringBuilder()
    private val imagenes = LinkedHashMap<String, Image>()
    private var contadorMarcos = 0

    companion object {
        /** Ancho util de una pagina A4 con margenes de 2 cm, en centimetros. */
        const val PAGE_WIDTH_CM = 17.0

        /** Ancho al que se pinta una foto en el documento. Dos por fila entran holgadas. */
        const val PHOTO_WIDTH_CM = 7.5
    }

    fun title(text: String) = apply { cuerpo.append(p("Title", text)) }
    fun heading1(text: String) = apply { cuerpo.append(h(1, "Heading_20_1", text)) }
    fun heading2(text: String) = apply { cuerpo.append(h(2, "Heading_20_2", text)) }
    fun body(text: String) = apply { cuerpo.append(p("Standard", text)) }
    fun meta(text: String) = apply { cuerpo.append(p("Meta", text)) }

    /** Un parrafo con varias lineas: cada salto de linea es un `<text:line-break/>`. */
    fun multiline(text: String) = apply {
        if (text.isBlank()) return@apply
        cuerpo.append("<text:p text:style-name=\"Standard\">")
        text.lines().forEachIndexed { i, l ->
            if (i > 0) cuerpo.append("<text:line-break/>")
            cuerpo.append(esc(l))
        }
        cuerpo.append("</text:p>\n")
    }

    /**
     * Una vista previa de foto, anclada como parrafo.
     *
     * El alto se deriva del ancho y de la proporcion REAL de la imagen. Fijar los dos a un
     * numero redondo deforma las fotos verticales, que en terreno son la mitad.
     */
    fun photo(img: Image, caption: String? = null) = apply {
        imagenes.getOrPut(img.name) { img }
        val alto = if (img.widthPx > 0)
            PHOTO_WIDTH_CM * img.heightPx / img.widthPx else PHOTO_WIDTH_CM
        contadorMarcos++
        cuerpo.append("<text:p text:style-name=\"Standard\">")
        cuerpo.append("<draw:frame draw:style-name=\"Photo\" draw:name=\"Image$contadorMarcos\" ")
        cuerpo.append("text:anchor-type=\"as-char\" ")
        cuerpo.append("svg:width=\"").append(cm(PHOTO_WIDTH_CM)).append("\" ")
        cuerpo.append("svg:height=\"").append(cm(alto)).append("\" draw:z-index=\"0\">")
        cuerpo.append("<draw:image xlink:href=\"Pictures/").append(esc(img.name))
        cuerpo.append("\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>")
        cuerpo.append("</draw:frame></text:p>\n")
        caption?.let { cuerpo.append(p("Meta", it)) }
    }

    fun rule() = apply { cuerpo.append("<text:p text:style-name=\"Rule\"/>\n") }

    private fun cm(v: Double) = "%.3fcm".format(java.util.Locale.ROOT, v)

    private fun p(style: String, text: String) =
        "<text:p text:style-name=\"$style\">${esc(text)}</text:p>\n"

    private fun h(level: Int, style: String, text: String) =
        "<text:h text:style-name=\"$style\" text:outline-level=\"$level\">${esc(text)}</text:h>\n"

    /** Escapa lo que XML no admite crudo. Un `&` en una nota de terreno rompe el documento. */
    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c == '\'' -> append("&apos;")
            c == '\t' -> append("<text:tab/>")
            // Los caracteres de control no son XML valido ni con entidad: se quitan. Llegan
            // desde teclados raros y desde texto pegado, y un solo byte de estos hace que el
            // documento entero no abra.
            c.code < 0x20 -> Unit
            else -> append(c)
        }
    }

    // ------------------------------------ el fichero ------------------------------------

    fun build(): ByteArray {
        val salida = ByteArrayOutputStream()
        ZipOutputStream(salida).use { zip ->
            // mimetype: primera y SIN comprimir. Es lo que hace que el fichero se reconozca.
            val mime = "application/vnd.oasis.opendocument.text".toByteArray()
            val e = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            }
            zip.putNextEntry(e); zip.write(mime); zip.closeEntry()

            fun texto(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            texto("META-INF/manifest.xml", manifest())
            texto("styles.xml", styles())
            texto("content.xml", content())
            imagenes.values.forEach { img ->
                zip.putNextEntry(ZipEntry("Pictures/${img.name}"))
                zip.write(img.bytes)
                zip.closeEntry()
            }
        }
        return salida.toByteArray()
    }

    private fun manifest(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<manifest:manifest """)
        append("""xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" """)
        append("""manifest:version="1.3">""").append('\n')
        append("""<manifest:file-entry manifest:full-path="/" """)
        append("""manifest:media-type="application/vnd.oasis.opendocument.text"/>""").append('\n')
        listOf("content.xml", "styles.xml").forEach {
            append("""<manifest:file-entry manifest:full-path="$it" """)
            append("""manifest:media-type="text/xml"/>""").append('\n')
        }
        imagenes.values.forEach { img ->
            append("""<manifest:file-entry manifest:full-path="Pictures/${img.name}" """)
            append("""manifest:media-type="${img.mediaType}"/>""").append('\n')
        }
        append("</manifest:manifest>\n")
    }

    /**
     * Los namespaces de ODF. En cadenas normales y no en una cruda: una cadena cruda no
     * procesa escapes, asi que la comilla final de `office:version="1.3"` no habria forma de
     * cerrarla y el XML saldria con una barra invertida dentro.
     */
    private fun ns(): String =
        "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
        "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
        "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
        "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" " +
        "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
        "xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" " +
        "xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
        "office:version=\"1.3\""

    private fun styles(): String = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles ${ns()}>
 <office:styles>
  <style:style style:name="Standard" style:family="paragraph">
   <style:paragraph-properties fo:margin-top="0cm" fo:margin-bottom="0.15cm"/>
   <style:text-properties fo:font-size="10pt"/>
  </style:style>
  <style:style style:name="Title" style:family="paragraph" style:parent-style-name="Standard">
   <style:paragraph-properties fo:margin-bottom="0.4cm"/>
   <style:text-properties fo:font-size="20pt" fo:font-weight="bold"/>
  </style:style>
  <style:style style:name="Heading_20_1" style:family="paragraph" style:parent-style-name="Standard">
   <style:paragraph-properties fo:margin-top="0.6cm" fo:margin-bottom="0.2cm"/>
   <style:text-properties fo:font-size="14pt" fo:font-weight="bold"/>
  </style:style>
  <style:style style:name="Heading_20_2" style:family="paragraph" style:parent-style-name="Standard">
   <style:paragraph-properties fo:margin-top="0.35cm" fo:margin-bottom="0.1cm"/>
   <style:text-properties fo:font-size="11pt" fo:font-weight="bold"/>
  </style:style>
  <style:style style:name="Meta" style:family="paragraph" style:parent-style-name="Standard">
   <style:text-properties fo:font-size="8.5pt" fo:font-style="italic" fo:color="#555555"/>
  </style:style>
  <style:style style:name="Rule" style:family="paragraph" style:parent-style-name="Standard">
   <style:paragraph-properties fo:margin-top="0.3cm" fo:margin-bottom="0.3cm"
     fo:border-bottom="0.5pt solid #aaaaaa" fo:padding-bottom="0.1cm"/>
  </style:style>
  <style:style style:name="Photo" style:family="graphic">
   <style:graphic-properties style:vertical-pos="top" style:vertical-rel="baseline"
     fo:margin-right="0.2cm" fo:margin-bottom="0.2cm"/>
  </style:style>
 </office:styles>
 <office:automatic-styles>
  <style:page-layout style:name="pm1">
   <style:page-layout-properties fo:page-width="21cm" fo:page-height="29.7cm"
     fo:margin-top="2cm" fo:margin-bottom="2cm" fo:margin-left="2cm" fo:margin-right="2cm"/>
  </style:page-layout>
 </office:automatic-styles>
 <office:master-styles>
  <style:master-page style:name="Standard" style:page-layout-name="pm1"/>
 </office:master-styles>
</office:document-styles>
"""

    private fun content(): String = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content ${ns()}>
 <office:body>
  <office:text>
$cuerpo  </office:text>
 </office:body>
</office:document-content>
"""
}
