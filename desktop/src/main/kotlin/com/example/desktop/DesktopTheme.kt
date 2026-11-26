package com.example.desktop

import com.example.ui.AppLanguage
import com.example.ui.theme.ThemeMode
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.Color
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

data class MediaXPalette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
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
    ): JPanel = JPanel().apply {
        isOpaque = true
        background = if (elevated) palette.surfaceElevated else palette.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(palette.border, 1, true),
            EmptyBorder(paddingV, paddingH, paddingV, paddingH),
        )
        putClientProperty(FlatClientProperties.STYLE, "arc: 16")
    }

    fun primaryButton(text: String): JButton = JButton(text).apply {
        background = palette.primary
        foreground = palette.onPrimary
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.BOLD, 13f)
        putClientProperty(FlatClientProperties.STYLE, "arc: 12; margin: 7,14,7,14")
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
        putClientProperty(FlatClientProperties.STYLE, "arc: 999; margin: 4,12,4,12")
    }

    fun styledField(placeholder: String): JTextField = JTextField().apply {
        background = palette.surfaceElevated
        foreground = palette.textPrimary
        caretColor = palette.primary
        putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder)
        putClientProperty(FlatClientProperties.STYLE, "arc: 12; margin: 6,10,6,10")
    }

    fun pillLabel(text: String, accent: Color = palette.badgeBg, fg: Color = palette.textPrimary): JLabel =
        JLabel(text).apply {
            isOpaque = true
            background = accent
            foreground = fg
            font = font.deriveFont(Font.BOLD, 11f)
            border = EmptyBorder(3, 8, 3, 8)
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
        bg = Color(0x0B1020),
        surface = Color(0x131B2E),
        surfaceElevated = Color(0x1B2640),
        border = Color(0x283552),
        primary = Color(0x38BDF8),
        onPrimary = Color(0x061322),
        secondary = Color(0x10B981),
        tertiary = Color(0xF59E0B),
        error = Color(0xF87171),
        textPrimary = Color(0xF1F5F9),
        textSecondary = Color(0x94A3B8),
        badgeBg = Color(0x22304F),
    )

    private fun lightPalette() = MediaXPalette(
        isDark = false,
        bg = Color(0xF3F6FB),
        surface = Color(0xFFFFFF),
        surfaceElevated = Color(0xEEF2F9),
        border = Color(0xD3DCEB),
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
