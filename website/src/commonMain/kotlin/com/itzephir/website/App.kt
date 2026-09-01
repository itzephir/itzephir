package com.itzephir.website

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itzephir.website.generated.resources.Res
import com.itzephir.website.generated.resources.avatar
import com.itzephir.website.generated.resources.roboto_f99820f9f1c7c171
import com.itzephir.website.generated.resources.roboto_mono_e7069fe300d4629b
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource

private val DustyPink = Color(0xFFC784B9)
private val IceBlue = Color(0xFF8FAEBD)
private val Ink = Color(0xFF171719)
private val Paper = Color(0xFFFFF9FC)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFDCA5D2),
    onPrimary = Color(0xFF44213C),
    primaryContainer = Color(0xFF5E3655),
    onPrimaryContainer = Color(0xFFFFD8F2),
    secondary = Color(0xFFA8C7D5),
    onSecondary = Color(0xFF113540),
    secondaryContainer = Color(0xFF294B57),
    onSecondaryContainer = Color(0xFFC4E7F5),
    tertiary = Color(0xFFF0B98C),
    onTertiary = Color(0xFF4B280D),
    tertiaryContainer = Color(0xFF65401F),
    onTertiaryContainer = Color(0xFFFFDCC0),
    background = Color(0xFF080809),
    onBackground = Color(0xFFF0EAEF),
    surface = Ink,
    onSurface = Color(0xFFF0EAEF),
    surfaceVariant = Color(0xFF252326),
    onSurfaceVariant = Color(0xFFC9C2C8),
    outline = Color(0xFF918991),
    outlineVariant = Color(0xFF3A373B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF87517E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8F2),
    onPrimaryContainer = Color(0xFF3A0A32),
    secondary = Color(0xFF476573),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCBEAF8),
    onSecondaryContainer = Color(0xFF062F3B),
    tertiary = Color(0xFF7B5635),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC0),
    onTertiaryContainer = Color(0xFF2C1603),
    background = Color(0xFFF1E9EE),
    onBackground = Color(0xFF211E21),
    surface = Paper,
    onSurface = Color(0xFF211E21),
    surfaceVariant = Color(0xFFF0E7ED),
    onSurfaceVariant = Color(0xFF514A50),
    outline = Color(0xFF81767E),
    outlineVariant = Color(0xFFD7CBD3),
)

private fun siteTypography(fontFamily: FontFamily, monoFontFamily: FontFamily) = Typography(
    displayLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = 76.sp,
        lineHeight = 76.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-3.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = fontFamily,
        fontSize = 52.sp,
        lineHeight = 54.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontSize = 28.sp,
        lineHeight = 33.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontSize = 18.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = monoFontFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
    ),
)

private data class Project(
    val number: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val url: String,
    val accent: Accent,
)

private enum class Accent { Pink, Blue, Amber, Neutral }

private val projects = listOf(
    Project(
        number = "01",
        name = "itzcast",
        description = "Быстрый расширяемый launcher для macOS: глобальный хоткей, fuzzy search и расширения через независимый JSONL-протокол.",
        tags = listOf("Kotlin Multiplatform", "Compose", "macOS"),
        url = "https://github.com/itzephir/itzcast",
        accent = Accent.Pink,
    ),
    Project(
        number = "02",
        name = "Photorus TGApp Backend",
        description = "Ktor-микросервисы для образовательного Telegram WebApp: OCR, AI-разбор правил, SSE и MongoDB.",
        tags = listOf("Kotlin", "Ktor", "Docker"),
        url = "https://github.com/itzephir/photorus-tgapp-backend",
        accent = Accent.Blue,
    ),
    Project(
        number = "03",
        name = "WhereRubles",
        description = "Android-приложение для учёта баланса личного счёта, сделанное во время Школы мобильной разработки Яндекса 2025.",
        tags = listOf("Kotlin", "Android", "Yandex SMR"),
        url = "https://github.com/itzephir/WhereRubles",
        accent = Accent.Amber,
    ),
    Project(
        number = "04",
        name = "Coffee",
        description = "Небольшое Android-приложение, которое каждый день встречает пользователя новым комплиментом.",
        tags = listOf("Kotlin", "Android", "Mobile"),
        url = "https://github.com/itzephir/Coffee",
        accent = Accent.Neutral,
    ),
    Project(
        number = "05",
        name = "T.Yurist",
        description = "Android-клиент продуктового учебного проекта T.Yurist.",
        tags = listOf("Kotlin", "Android", "Product"),
        url = "https://github.com/itzephir/T.Yurist-android",
        accent = Accent.Pink,
    ),
    Project(
        number = "06",
        name = "Storyline",
        description = "Простое Android-приложение для писателей и работы с историями.",
        tags = listOf("Kotlin", "Android", "Writing"),
        url = "https://github.com/itzephir/Storyline",
        accent = Accent.Blue,
    ),
)

@Composable
fun App(
    openLink: (String) -> Unit,
    onReady: () -> Unit = {},
) {
    var darkTheme by remember { mutableStateOf(true) }
    val siteSans = FontFamily(
        Font(Res.font.roboto_f99820f9f1c7c171, FontWeight.Normal),
        Font(Res.font.roboto_f99820f9f1c7c171, FontWeight.Medium),
        Font(Res.font.roboto_f99820f9f1c7c171, FontWeight.Bold),
        Font(Res.font.roboto_f99820f9f1c7c171, FontWeight.Black),
    )
    val siteMono = FontFamily(
        Font(Res.font.roboto_mono_e7069fe300d4629b, FontWeight.Normal),
        Font(Res.font.roboto_mono_e7069fe300d4629b, FontWeight.Bold),
    )

    LaunchedEffect(Unit) { onReady() }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = siteTypography(siteSans, siteMono),
    ) {
        FramedPortfolio(
            darkTheme = darkTheme,
            toggleTheme = { darkTheme = !darkTheme },
            openLink = openLink,
        )
    }
}

@Composable
private fun FramedPortfolio(
    darkTheme: Boolean,
    toggleTheme: () -> Unit,
    openLink: (String) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val compact = maxWidth < 720.dp

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (compact) 20.dp else 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(compact, darkTheme, toggleTheme)
                Box(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 1180.dp),
                ) {
                    Column {
                        Hero(compact, openLink)
                        Spacer(Modifier.height(if (compact) 76.dp else 118.dp))
                        About(compact)
                        Spacer(Modifier.height(if (compact) 76.dp else 118.dp))
                        Projects(compact, openLink)
                        Spacer(Modifier.height(if (compact) 76.dp else 118.dp))
                        Contact(compact, openLink)
                        Footer(compact)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    compact: Boolean,
    darkTheme: Boolean,
    toggleTheme: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 1180.dp)
            .padding(vertical = if (compact) 22.dp else 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            MonoText("ITZEPHIR / PORTFOLIO", MaterialTheme.colorScheme.onSurface)
            MonoText("MOSCOW  ·  2026", MaterialTheme.colorScheme.onSurfaceVariant, small = true)
        }
        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier
                .clickable(role = Role.Button, onClick = toggleTheme)
                .semantics { contentDescription = "Переключить тему" },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = if (compact) 14.dp else 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (darkTheme) IceBlue else DustyPink),
                )
                MonoText(if (darkTheme) "LIGHT" else "DARK", MaterialTheme.colorScheme.onSurfaceVariant, small = true)
            }
        }
    }
}

@Composable
private fun Hero(compact: Boolean, openLink: (String) -> Unit) {
    if (compact) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 38.dp)) {
            HeroCopy(compact, openLink)
            Spacer(Modifier.height(44.dp))
            Portrait(260.dp)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 74.dp),
            horizontalArrangement = Arrangement.spacedBy(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1.25f)) { HeroCopy(compact, openLink) }
            Box(Modifier.weight(0.75f), contentAlignment = Alignment.Center) {
                Portrait(320.dp)
            }
        }
    }
}

@Composable
private fun HeroCopy(compact: Boolean, openLink: (String) -> Unit) {
    val buttonPadding = PaddingValues(
        horizontal = if (compact) 18.dp else 26.dp,
        vertical = 17.dp,
    )
    Column {
        Eyebrow("// HELLO, WORLD")
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Дмитрий\nДворянников",
            style = if (compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Mobile & Backend Kotlin Developer",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(8.dp))
        MonoText(
            text = "ЯНДЕКС  /  ЛЕКТОР ШМР 2026",
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Делаю продукты, которыми хочется пользоваться: от мобильных интерфейсов до надёжных серверных систем.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 630.dp),
        )
        Spacer(Modifier.height(30.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { openLink("https://github.com/itzephir") },
                shape = CircleShape,
                contentPadding = buttonPadding,
            ) {
                Text("Смотреть GitHub  >")
            }
            FilledTonalButton(
                onClick = { openLink("https://t.me/ItzEphir") },
                shape = CircleShape,
                contentPadding = buttonPadding,
            ) {
                Text("Написать мне")
            }
        }
    }
}

@Composable
private fun Portrait(size: Dp) {
    Image(
        painter = painterResource(Res.drawable.avatar),
        contentDescription = "Дмитрий Дворянников",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
    )
}

@Composable
private fun About(compact: Boolean) {
    SectionHeading("01", "Обо мне", compact)
    Spacer(Modifier.height(28.dp))
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AboutText()
            SkillPanel()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1.2f)) { AboutText() }
            Box(Modifier.weight(0.8f)) { SkillPanel() }
        }
    }
}

@Composable
private fun AboutText() {
    Surface(
        shape = RoundedCornerShape(34.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(30.dp)) {
            Eyebrow("README.MD")
            Spacer(Modifier.height(22.dp))
            Text(
                "Я Kotlin-разработчик из Москвы, работаю в Яндексе. В 2026 году — лектор Школы мобильной разработки. Люблю задачи на стыке инженерии и продукта: продумывать архитектуру, собирать выразительный UI и доводить идею до работающего релиза.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "В фокусе — Kotlin Multiplatform, Compose, Android и backend на Ktor. Иногда ухожу в Swift, инфраструктуру и инструменты для разработчиков.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillPanel() {
    Surface(
        shape = RoundedCornerShape(34.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(30.dp)) {
            MonoText("STACK / NOW", MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(22.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("KOTLIN", "KMP", "COMPOSE", "KTOR", "ANDROID", "SWIFT", "DOCKER").forEach { skill ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        MonoText(skill, MaterialTheme.colorScheme.onSurface, Modifier.padding(horizontal = 13.dp, vertical = 9.dp), small = true)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            MonoText("STATUS", MaterialTheme.colorScheme.onSecondaryContainer, small = true)
            Spacer(Modifier.height(6.dp))
            Text("Строю, исследую, выпускаю.", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun Projects(compact: Boolean, openLink: (String) -> Unit) {
    SectionHeading("02", "Избранные проекты", compact)
    Spacer(Modifier.height(28.dp))
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            projects.forEach { ProjectCard(it, openLink) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            projects.chunked(2).forEachIndexed { rowIndex, rowProjects ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowProjects.forEach { project ->
                        Box(Modifier.weight(1f)) {
                            ProjectCard(project, openLink, tall = rowIndex == 0)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectCard(project: Project, openLink: (String) -> Unit, tall: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val container = when (project.accent) {
        Accent.Pink -> colors.primaryContainer
        Accent.Blue -> colors.secondaryContainer
        Accent.Amber -> colors.tertiaryContainer
        Accent.Neutral -> colors.surfaceVariant
    }
    val content = when (project.accent) {
        Accent.Pink -> colors.onPrimaryContainer
        Accent.Blue -> colors.onSecondaryContainer
        Accent.Amber -> colors.onTertiaryContainer
        Accent.Neutral -> colors.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (tall) 330.dp else 290.dp)
            .clickable(role = Role.Button) { openLink(project.url) }
            .semantics { contentDescription = "Открыть проект ${project.name}" },
        shape = RoundedCornerShape(36.dp),
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText("PROJECT / ${project.number}", content)
                Spacer(Modifier.weight(1f))
                Surface(shape = CircleShape, color = content, contentColor = container) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Text(">", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(project.name, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(project.description, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.86f))
            Spacer(Modifier.weight(1f))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                project.tags.forEach { tag ->
                    Surface(
                        shape = CircleShape,
                        color = content.copy(alpha = 0.10f),
                        contentColor = content,
                    ) {
                        MonoText(tag.uppercase(), content, Modifier.padding(horizontal = 11.dp, vertical = 7.dp), small = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Contact(compact: Boolean, openLink: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(if (compact) 38.dp else 54.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        if (compact) {
            Column(Modifier.padding(30.dp)) {
                ContactCopy(compact)
                Spacer(Modifier.height(30.dp))
                ContactLinks(openLink)
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1.2f)) { ContactCopy(compact) }
                Box(Modifier.weight(0.8f)) { ContactLinks(openLink) }
            }
        }
    }
}

@Composable
private fun ContactCopy(compact: Boolean) {
    Column {
        MonoText("03 / CONTACT", MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.height(20.dp))
        Text(
            "Давайте сделаем\nчто-нибудь классное.",
            style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium,
        )
    }
}

@Composable
private fun ContactLinks(openLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContactButton("TELEGRAM", "@ItzEphir") { openLink("https://t.me/ItzEphir") }
        ContactButton("EMAIL", "d.y.dvoryannikov@mail.ru") { openLink("mailto:d.y.dvoryannikov@mail.ru") }
        ContactButton("GITHUB", "itzephir") { openLink("https://github.com/itzephir") }
    }
}

@Composable
private fun ContactButton(label: String, value: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onPrimary,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MonoText(label, MaterialTheme.colorScheme.primary, small = true)
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Text(">", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun Footer(compact: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 46.dp, bottom = if (compact) 30.dp else 42.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(20.dp))
        if (compact) {
            MonoText("BUILT WITH KOTLIN / WASM", MaterialTheme.colorScheme.onSurfaceVariant, small = true)
            Spacer(Modifier.height(6.dp))
            MonoText("ITZEPHIR.COM  ·  2026", MaterialTheme.colorScheme.onSurfaceVariant, small = true)
        } else {
            Row {
                MonoText("BUILT WITH KOTLIN / COMPOSE / WASM", MaterialTheme.colorScheme.onSurfaceVariant, small = true)
                Spacer(Modifier.weight(1f))
                MonoText("ITZEPHIR.COM  ·  MOSCOW  ·  2026", MaterialTheme.colorScheme.onSurfaceVariant, small = true)
            }
        }
    }
}

@Composable
private fun SectionHeading(number: String, title: String, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.secondary,
        ) {
            MonoText(number, MaterialTheme.colorScheme.secondary, Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.width(18.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Eyebrow(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.secondary,
    ) {
        MonoText(text, MaterialTheme.colorScheme.secondary, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), small = true)
    }
}

@Composable
private fun MonoText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = if (small) 11.sp else 13.sp,
            lineHeight = if (small) 15.sp else 18.sp,
        ),
    )
}
