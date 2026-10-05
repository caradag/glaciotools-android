package cl.umag.glaciertemp.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Exportar: guardarlo en el telefono, o mandarlo ya --por WhatsApp, por correo-- sin pasar
 * por el gestor de ficheros.
 *
 * EN TERRENO LO NORMAL ES COMPARTIR. Guardar y despues buscar el fichero para adjuntarlo son
 * dos aplicaciones y una carpeta que recordar, con frio y con una senal de red que puede irse
 * en cualquier momento. Compartir es un paso: el menu del sistema con las apps que pueden
 * recibirlo.
 *
 * Lo compartido se escribe primero en la cache de la app y se entrega por el mismo
 * FileProvider que usan las fotos, con permiso de lectura solo para ese fichero. La carpeta
 * se vacia antes de cada exportacion: es un buzon de paso, no un archivo.
 */
@Stable
class SaveOrShare internal constructor() {
    internal data class Pending(
        val name: String,
        val shareMime: String,
        val write: (OutputStream) -> Unit,
        val onResult: (ok: Boolean, shared: Boolean) -> Unit,
    )
    internal var pending by mutableStateOf<Pending?>(null)

    /**
     * Pregunta si guardar o compartir y lo hace.
     *
     * @param write escribe el contenido. Corre FUERA del hilo principal.
     */
    fun offer(name: String, shareMime: String, write: (OutputStream) -> Unit,
              onResult: (ok: Boolean, shared: Boolean) -> Unit = { _, _ -> }) {
        pending = Pending(name, shareMime, write, onResult)
    }
}

/** El directorio de paso de lo compartido. Tiene que coincidir con file_paths.xml. */
private fun buzon(ctx: Context): File = File(ctx.cacheDir, "exports")

/**
 * Deja el fichero en el buzon y abre el menu de compartir. Devuelve false si no se pudo
 * escribir. Hay que llamarlo fuera del hilo principal para escribir, y en el principal para
 * lanzar el menu: por eso se parte en [prepareShare] y [launchShare].
 */
fun prepareShare(ctx: Context, name: String, write: (OutputStream) -> Unit): File? = runCatching {
    val dir = buzon(ctx)
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, name.replace('/', '_'))
    f.outputStream().use(write)
    f
}.getOrNull()

fun launchShare(ctx: Context, f: File, mime: String) {
    val uri = MediaVault.uriFor(ctx, f)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, f.name)
        // ClipData ademas del EXTRA_STREAM: es lo que hace que el permiso de lectura llegue
        // a la app elegida en el selector. Sin el, algunas lo reciben y no pueden abrirlo.
        clipData = android.content.ClipData.newUri(ctx.contentResolver, f.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(envio, "Share ${f.name}")
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * El cuadro de guardar o compartir.
 *
 * @param saveMime el tipo que se le pasa al cuadro del sistema al GUARDAR; se fija al crear
 *   el lanzador, por eso va aqui y no en cada [SaveOrShare.offer].
 */
@Composable
fun rememberSaveOrShare(saveMime: String, tag: String = "export"): SaveOrShare {
    val ctx = LocalContext.current
    val estado = remember { SaveOrShare() }
    val alcance = rememberCoroutineScope()
    var aGuardar by remember { mutableStateOf<SaveOrShare.Pending?>(null) }

    val lanzador = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(saveMime)
    ) { uri ->
        val p = aGuardar ?: return@rememberLauncherForActivityResult
        aGuardar = null
        if (uri == null) { p.onResult(false, false); return@rememberLauncherForActivityResult }
        alcance.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use(p.write) != null }
                    .getOrDefault(false)
            }
            p.onResult(ok, false)
        }
    }

    estado.pending?.let { p ->
        AlertDialog(
            onDismissRequest = { estado.pending = null },
            modifier = Modifier.testTag("$tag-choice"),
            title = { Text("Export") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    Text("Save it in the phone, or send it straight away by e-mail, " +
                         "WhatsApp or any app that takes files.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                               estado.pending = null
                               alcance.launch {
                                   val f = withContext(Dispatchers.IO) { prepareShare(ctx, p.name, p.write) }
                                   if (f == null) p.onResult(false, true)
                                   else { launchShare(ctx, f, p.shareMime); p.onResult(true, true) }
                               }
                           },
                           modifier = Modifier.testTag("$tag-share")) {
                    Icon(Icons.Outlined.Share, null); Text("  Share")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                               estado.pending = null
                               aGuardar = p
                               lanzador.launch(p.name)
                           },
                           modifier = Modifier.testTag("$tag-save")) {
                    Icon(Icons.Outlined.Save, null); Text("  Save to device")
                }
            })
    }
    return estado
}
