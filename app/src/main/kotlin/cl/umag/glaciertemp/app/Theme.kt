package cl.umag.glaciertemp.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Paleta tomada del logo de GlacioTools.
 *
 * Los tres colores salen de contar los pixeles del icono, no de elegirlos a ojo: el azul
 * marino del fondo, el celeste claro de la montana y el azul medio del circuito. Asi la app
 * y el icono se reconocen como lo mismo.
 */
private val Navy = Color(0xFF0B2A4A)        // fondo del logo
private val IceBlue = Color(0xFF99E5FE)     // trazo claro de la montana
private val CircuitBlue = Color(0xFF3EB7EF) // pistas del circuito
private val DeepBlue = Color(0xFF1F4C6E)    // azul intermedio del logo
private val SteelBlue = Color(0xFF2B6F97)

private val Claro = lightColorScheme(
    // En claro manda el azul medio: el marino sobre blanco resulta pesado para los botones,
    // y ademas no se distingue del texto.
    primary = SteelBlue,
    onPrimary = Color.White,
    primaryContainer = IceBlue,
    onPrimaryContainer = Navy,
    secondary = CircuitBlue,
    onSecondary = Navy,
    secondaryContainer = Color(0xFFD6EEFB),
    onSecondaryContainer = Navy,
    background = Color(0xFFF7FBFE),
    onBackground = Navy,
    surface = Color(0xFFF7FBFE),
    onSurface = Navy,
    surfaceVariant = Color(0xFFE3EEF6),
    onSurfaceVariant = DeepBlue,
    outline = Color(0xFF7DA0B8),
    error = Color(0xFFB3261E),
)

private val Oscuro = darkColorScheme(
    // En oscuro el marino pasa a ser el fondo, que es su papel en el logo.
    primary = CircuitBlue,
    onPrimary = Navy,
    primaryContainer = DeepBlue,
    onPrimaryContainer = IceBlue,
    secondary = IceBlue,
    onSecondary = Navy,
    background = Navy,
    onBackground = Color(0xFFE3F2FB),
    surface = Color(0xFF102F52),
    onSurface = Color(0xFFE3F2FB),
    surfaceVariant = Color(0xFF17395F),
    onSurfaceVariant = Color(0xFFAFD3E8),
    outline = SteelBlue,
    error = Color(0xFFFF8A80),
)

/**
 * Los dos tonos del logotipo.
 *
 * "Glacio" va en el azul del logo, que se lee sobre fondo claro y sobre el marino. "Tools"
 * NO puede ser el marino fijo: en modo oscuro el fondo ES ese marino y la palabra
 * desaparecia. Se toma del esquema, asi que sale marino sobre claro y casi blanco sobre
 * oscuro, que es lo que hace el logotipo en sus dos versiones.
 */
val WordmarkBlue = Color(0xFF1E88C4)

/** Azul mas claro para el modo oscuro, del propio icono. */
val WordmarkBlueDark = Color(0xFF3EB7EF)

@Composable
fun GlacioToolsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Oscuro else Claro,
        content = content,
    )
}
