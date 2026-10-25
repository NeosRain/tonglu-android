package cn.tonglu.app.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetAppearanceTest {
    @Test fun defaultPreservesOpaqueCardAndOriginalRadius() {
        assertEquals(1f, WidgetAppearance().alpha, 0f)
        assertEquals(20, WidgetAppearance().radiusDp)
    }
    @Test fun invalidPreferencesCannotMakeTextBackgroundDisappear() {
        assertEquals(WidgetAppearance(85,12), WidgetAppearance(-1,-999).normalized())
        assertEquals(WidgetAppearance(100,28), WidgetAppearance(999,999).normalized())
        assertEquals(.85f, WidgetAppearance(-1).alpha, .0001f)
    }
    @Test fun validUserValuesRemainUnchangedAndNormalizationIsStable() {
        val style = WidgetAppearance(92,28)
        assertEquals(style, style.normalized())
        assertEquals(style.normalized(), style.normalized().normalized())
        assertEquals(.92f, style.alpha, .0001f)
    }
}
