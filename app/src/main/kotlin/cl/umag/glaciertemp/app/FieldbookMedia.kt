package cl.umag.glaciertemp.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cl.umag.glaciertemp.core.fieldbook.FieldbookExport
import cl.umag.glaciertemp.core.fieldbook.FieldbookStore
import cl.umag.glaciertemp.core.fieldbook.OdtWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * De donde saca la exportacion los bytes de las fotos y los audios.
 *
 * Es la pieza que `:core` no puede tener: decodificar y reescalar un JPEG necesita el
 * decodificador de Android. `:core` define la interfaz y hace todo lo demas --los CSV, el ODT,
 * el zip-- de forma que se prueba entera en el escritorio con una fuente falsa.
 */
class FieldbookMedia(private val store: FieldbookStore) : FieldbookExport.Media {

    companion object {
        /**
         * Lado mayor de la vista previa que se incrusta en el documento, en pixeles.
         *
         * A 7,5 cm de ancho impreso, 1000 px dan unos 340 ppp: mas resolucion no se ve y si se
         * nota en el tamano del fichero. Una campana de doscientas fotos a tamano original
         * daria un ODT de cientos de megabytes que ningun procesador de textos abre.
         */
        const val PREVIEW_MAX_PX = 1000

        const val PREVIEW_QUALITY = 78
    }

    override fun open(name: String): InputStream? {
        val f = store.media(name)
        return if (f.exists() && f.length() > 0L) f.inputStream() else null
    }

    /**
     * Una version reducida en JPEG, con sus dimensiones reales.
     *
     * Las dimensiones viajan porque el documento necesita la PROPORCION para no deformar la
     * foto: fijar ancho y alto a numeros redondos estira las verticales, que en terreno son la
     * mitad. Devuelve null si la foto ya no esta o no se puede decodificar, y quien llama lo
     * dice en el documento en vez de dejar un hueco mudo.
     */
    override fun preview(name: String): OdtWriter.Image? {
        val f = store.media(name)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            val medir = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, medir)
            val mayor = maxOf(medir.outWidth, medir.outHeight)
            if (mayor <= 0) return null
            var escala = 1
            while (mayor / (escala * 2) >= PREVIEW_MAX_PX) escala *= 2
            val bmp = BitmapFactory.decodeFile(
                f.absolutePath, BitmapFactory.Options().apply { inSampleSize = escala })
                ?: return null
            val salida = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, salida)
            val img = OdtWriter.Image(
                // El nombre dentro del ODT se mantiene: es la unica forma de relacionar la
                // vista previa del documento con el fichero original de su carpeta.
                name = name,
                bytes = salida.toByteArray(),
                widthPx = bmp.width,
                heightPx = bmp.height)
            bmp.recycle()
            img
        }.getOrNull()
    }
}
