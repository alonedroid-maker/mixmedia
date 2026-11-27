package com.example.desktop

import com.example.ui.AppLanguage
import com.example.ui.theme.ThemeMode
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

data class MediaXPalette(
    val isDark: Boolean,
    val bg: Color,
    val sidebarBg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val navActiveBg: Color,
    val hoverBg: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val border: Color,
    val cardHoverBorder: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val tertiary: Color,
    val error: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val badgeBg: Color,
)

object DesktopTheme {

    @Volatile
    var palette: MediaXPalette = darkPalette()
        private set

    fun isDark(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> DesktopPlatformBridge.detectSystemDarkMode()
    }

    fun apply(mode: ThemeMode, language: AppLanguage) {
        val useDark = isDark(mode)
        palette = if (useDark) darkPalette() else lightPalette()

        UIManager.put("Component.arc", 14)
        UIManager.put("Button.arc", 12)
        UIManager.put("TextComponent.arc", 12)
        UIManager.put("ScrollBar.width", 10)
        UIManager.put("ScrollBar.thumbArc", 999)
        UIManager.put("TitlePane.unifiedBackground", true)

        if (useDark) {
            FlatDarkLaf.setup()
        } else {
            FlatLightLaf.setup()
        }

        val uiFont = pickBestUiFont(language)
        UIManager.put("defaultFont", uiFont)
        UIManager.put("Panel.background", palette.bg)
        UIManager.put("OptionPane.background", palette.surface)
        UIManager.put("OptionPane.messageForeground", palette.textPrimary)
        UIManager.put("Component.focusColor", palette.primary)
        UIManager.put("Component.focusedBorderColor", palette.primary)
    }

    fun applyOrientation(component: Component, language: AppLanguage) {
        val orientation = if (language == AppLanguage.FA) {
            ComponentOrientation.RIGHT_TO_LEFT
        } else {
            ComponentOrientation.LEFT_TO_RIGHT
        }
        component.applyComponentOrientation(orientation)
    }

    fun pickBestUiFont(language: AppLanguage, size: Int = 13): Font {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val candidates = if (language == AppLanguage.FA) {
            listOf("Vazirmatn", "Vazir", "IRANSans", "Segoe UI", "Tahoma", "Arial", Font.SANS_SERIF)
        } else {
            listOf("Segoe UI", "Inter", "Tahoma", "Arial", Font.SANS_SERIF)
        }
        val chosen = candidates.firstOrNull { available.contains(it) } ?: Font.SANS_SERIF
        return Font(chosen, Font.PLAIN, size)
    }

    fun styledCard(
        elevated: Boolean = false,
        paddingV: Int = 14,
        paddingH: Int = 16,
        hoverable: Boolean = false,
        selected: Boolean = false,
    ): RoundedCardPanel = RoundedCardPanel(
        elevated = elevated,
        hoverable = hoverable,
        selected = selected,
        paddingV = paddingV,
        paddingH = paddingH,
    )

    fun heroCard(
        paddingV: Int = 16,
        paddingH: Int = 18,
    ): GradientHeroPanel = GradientHeroPanel(
        paddingV = paddingV,
        paddingH = paddingH,
    )

    fun primaryButton(text: String): JButton = JButton(text).apply {
        background = palette.primary
        foreground = palette.onPrimary
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.BOLD, 13f)
        putClientProperty(FlatClientProperties.STYLE, "arc: 12; margin: 7,15,7,15")
    }

    fun secondaryButton(text: String): JButton = JButton(text).apply {
        background = palette.surfaceElevated
        foreground = palette.textPrimary
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        putClientProperty(FlatClientProperties.STYLE, "arc: 12; margin: 6,12,6,12")
    }

    fun chipButton(text: String, selected: Boolean = false): JButton = JButton(text).apply {
        background = if (selected) palette.primary else palette.badgeBg
        foreground = if (selected) palette.onPrimary else palette.textPrimary
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(if (selected) Font.BOLD else Font.PLAIN, 12f)
        putClientProperty(FlatClientProperties.STYLE, "arc: 999; margin: 5,13,5,13")
    }

    fun navButton(text: String, glyph: String, selected: Boolean = false): JButton = JButton("$glyph   $text").apply {
        horizontalAlignment = SwingConstants.LEADING
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        background = if (selected) palette.navActiveBg else palette.sidebarBg
        foreground = if (selected) palette.primary else palette.textPrimary
        font = font.deriveFont(if (selected) Font.BOLD else Font.PLAIN, 13.5f)
        putClientProperty(
            FlatClientProperties.STYLE,
            if (selected) "arc: 14; margin: 10,14,10,14" else "arc: 14; margin: 10,14,10,14",
        )
    }

    fun styledField(placeholder: String): JTextField = JTextField().apply {
        background = palette.surfaceElevated
        foreground = palette.textPrimary
        caretColor = palette.primary
        putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder)
        putClientProperty(FlatClientProperties.STYLE, "arc: 12; margin: 7,12,7,12")
    }

    fun pillLabel(text: String, accent: Color = palette.badgeBg, fg: Color = palette.textPrimary): JLabel =
        JLabel(text).apply {
            isOpaque = true
            background = accent
            foreground = fg
            font = font.deriveFont(Font.BOLD, 11f)
            border = EmptyBorder(3, 9, 3, 9)
            putClientProperty(FlatClientProperties.STYLE, "arc: 999")
        }

    fun sectionTitle(text: String): JLabel = JLabel(text).apply {
        foreground = palette.textPrimary
        font = font.deriveFont(Font.BOLD, 16f)
    }

    fun mutedLabel(text: String): JLabel = JLabel(text).apply {
        foreground = palette.textSecondary
        font = font.deriveFont(Font.PLAIN, 12f)
    }

    fun pad(v: Int = 10, h: Int = 12): EmptyBorder = EmptyBorder(v, h, v, h)

    fun setHandCursor(vararg comps: JComponent) {
        comps.forEach { it.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
    }

    private fun darkPalette() = MediaXPalette(
        isDark = true,
        bg = Color(0x090E1A),
        sidebarBg = Color(0x0E1526),
        surface = Color(0x131C31),
        surfaceElevated = Color(0x1A2642),
        navActiveBg = Color(0x1E3154),
        hoverBg = Color(0x213052),
        heroStart = Color(0x152544),
        heroEnd = Color(0x1A1838),
        border = Color(0x283655),
        cardHoverBorder = Color(0x38BDF8),
        primary = Color(0x38BDF8),
        onPrimary = Color(0x061322),
        secondary = Color(0x10B981),
        tertiary = Color(0xF59E0B),
        error = Color(0xF87171),
        textPrimary = Color(0xF1F5F9),
        textSecondary = Color(0x94A3B8),
        badgeBg = Color(0x223151),
    )

    private fun lightPalette() = MediaXPalette(
        isDark = false,
        bg = Color(0xF1F5FB),
        sidebarBg = Color(0xE8EEF8),
        surface = Color(0xFFFFFF),
        surfaceElevated = Color(0xF6F9FE),
        navActiveBg = Color(0xDBEAFE),
        hoverBg = Color(0xEAF1FD),
        heroStart = Color(0xE0F2FE),
        heroEnd = Color(0xEDE9FE),
        border = Color(0xCBD7EA),
        cardHoverBorder = Color(0x0284C7),
        primary = Color(0x0284C7),
        onPrimary = Color(0xFFFFFF),
        secondary = Color(0x059669),
        tertiary = Color(0xD97706),
        error = Color(0xDC2626),
        textPrimary = Color(0x0F172A),
        textSecondary = Color(0x475569),
        badgeBg = Color(0xE2E8F0),
    )
}

/**
 * Smooth gradient hero card painted in pure Java2D (compatible with Windows 7..11+).
 */
class GradientHeroPanel(
    private val arc: Int = 18,
    paddingV: Int = 16,
    paddingH: Int = 18,
) : JPanel() {

    init {
        isOpaque = false
        border = EmptyBorder(paddingV, paddingH, paddingV, paddingH)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toFloat()
            val h = height.toFloat()
            val p = DesktopTheme.palette
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc.toFloat(), arc.toFloat())
            g2.paint = GradientPaint(0f, 0f, p.heroStart, w, h, p.heroEnd)
            g2.fill(shape)
            g2.color = p.border
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * Rounded card surface with optional hover highlight & active selection accent.
 */
class RoundedCardPanel(
    private val elevated: Boolean = false,
    private val hoverable: Boolean = false,
    var isSelectedCard: Boolean = false,
    private val arc: Int = 16,
    paddingV: Int = 14,
    paddingH: Int = 16,
    selected: Boolean = false,
) : JPanel() {

    private var hovered = false

    init {
        isSelectedCard = selected
        isOpaque = false
        border = EmptyBorder(paddingV, paddingH, paddingV, paddingH)
        if (hoverable) {
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        hovered = false
                        repaint()
                    }
                }
            )
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val p = DesktopTheme.palette
            val w = width.toFloat()
            val h = height.toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc.toFloat(), arc.toFloat())
            val bgColor = when {
                isSelectedCard -> p.navActiveBg
                hovered && hoverable -> p.hoverBg
                elevated -> p.surfaceElevated
                else -> p.surface
            }
            val borderColor = when {
                isSelectedCard -> p.primary
                hovered && hoverable -> p.cardHoverBorder
                else -> p.border
            }
            g2.color = bgColor
            g2.fill(shape)
            g2.color = borderColor
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * Rounded poster thumbnail panel with cover-crop scaling, dark bottom scrim,
 * media-type pill badge, and golden IMDb/TMDB rating badge.
 */
class RoundedPosterPanel(
    posterWidth: Int,
    posterHeight: Int,
    private val mediaTypeBadge: String,
    private val ratingBadge: String? = null,
) : JPanel(BorderLayout()) {

    @Volatile
    private var posterImage: Image? = null

    init {
        isOpaque = false
        preferredSize = Dimension(posterWidth, posterHeight)
        minimumSize = Dimension(posterWidth, posterHeight)
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
    }

    fun setPosterImage(image: Image?) {
        posterImage = image
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val p = DesktopTheme.palette
            val w = width
            val h = height
            val clip = RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), 14f, 14f)
            g2.clip = clip

            val img = posterImage
            if (img != null) {
                val iw = img.getWidth(null)
                val ih = img.getHeight(null)
                if (iw > 0 && ih > 0) {
                    val scale = maxOf(w.toDouble() / iw, h.toDouble() / ih)
                    val dw = (iw * scale).toInt()
                    val dh = (ih * scale).toInt()
                    val dx = (w - dw) / 2
                    val dy = (h - dh) / 2
                    g2.drawImage(img, dx, dy, dw, dh, null)
                } else {
                    g2.drawImage(img, 0, 0, w, h, null)
                }
                // Subtle bottom readability gradient scrim
                g2.paint = GradientPaint(
                    0f,
                    (h * 0.55f),
                    Color(0, 0, 0, 0),
                    0f,
                    h.toFloat(),
                    Color(6, 12, 24, 185),
                )
                g2.fillRect(0, (h * 0.55f).toInt(), w, h)
            } else {
                g2.paint = GradientPaint(0f, 0f, p.surface, w.toFloat(), h.toFloat(), p.heroStart)
                g2.fillRect(0, 0, w, h)
                g2.color = p.textSecondary
                g2.font = font.deriveFont(Font.BOLD, 13f)
                val fm = g2.fontMetrics
                val tx = (w - fm.stringWidth(mediaTypeBadge)) / 2
                val ty = (h + fm.ascent) / 2
                g2.drawString(mediaTypeBadge, tx, ty)
            }

            // Top-left media type pill
            val badgeFont = font.deriveFont(Font.BOLD, 10f)
            g2.font = badgeFont
            val fm = g2.fontMetrics
            val typePadW = fm.stringWidth(mediaTypeBadge) + 12
            val pillH = 18
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.88f)
            g2.color = p.bg
            g2.fillRoundRect(6, 6, typePadW, pillH, 10, 10)
            g2.composite = AlphaComposite.SrcOver
            g2.color = p.textPrimary
            g2.drawString(mediaTypeBadge, 12, 6 + fm.ascent + 2)

            // Top-right golden rating pill
            if (!ratingBadge.isNullOrBlank()) {
                val rW = fm.stringWidth(ratingBadge) + 12
                val rx = (w - rW - 6).coerceAtLeast(typePadW + 10)
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.90f)
                g2.color = Color(0x1E, 0x1B, 0x0E)
                g2.fillRoundRect(rx, 6, rW, pillH, 10, 10)
                g2.composite = AlphaComposite.SrcOver
                g2.color = Color(0xFB, 0xBF, 0x24)
                g2.drawString(ratingBadge, rx + 6, 6 + fm.ascent + 2)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
