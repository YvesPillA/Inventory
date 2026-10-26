package com.fieldstock.app.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fieldstock.app.data.InventoryRepository
import com.fieldstock.app.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LanguageSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun clean() {
        context.getSharedPreferences("fieldstock", 0).edit().clear().commit()
        context.deleteDatabase(InventoryRepository.DATABASE_NAME)
        AppLanguage.current = AppLanguage.CHINESE
    }
    @After fun resetLanguage() { AppLanguage.current = AppLanguage.CHINESE }

    @Test fun firstUseHasNoChoiceAndSelectionPersistsIndependentlyOfDeviceLocale() {
        assertNull(LanguageSettings.selected(context))
        LanguageSettings.select(context, AppLanguage.ENGLISH)
        AppLanguage.current = AppLanguage.CHINESE
        val localized = LanguageSettings.localizedContext(context)
        assertEquals(AppLanguage.ENGLISH, LanguageSettings.selected(localized))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.current)
        assertEquals("en", localized.resources.configuration.locales[0].language)
        assertEquals("Saved", tr(TextKey.SAVED))
    }

    @Test fun changingLanguageKeepsInventorySerialRulesAndOtherPreferences() {
        InventoryRepository(context).use { repository ->
            val country = repository.addCountry("中文 Country")
            val warehouse = repository.addWarehouse(country.id, "Normal 正常")
            context.getSharedPreferences("fieldstock", 0).edit().putString("warehouse", warehouse.id).commit()
            val input = RecordInput(repository.snapshot().tasks.first().id, warehouse.id, ItemKind.MACHINE,
                "000aB", "000a-0B", 1, StockCondition.NORMAL, "原样 English")
            repository.save(input)
            val before = repository.snapshot()
            for (language in AppLanguage.entries.reversed()) {
                LanguageSettings.select(context, language)
                LanguageSettings.localizedContext(context)
                assertEquals(before, repository.snapshot())
                assertEquals(warehouse.id, context.getSharedPreferences("fieldstock", 0).getString("warehouse", null))
                assertThrows(DuplicateSnException::class.java) { repository.save(input) }
                assertEquals(before, repository.snapshot())
            }
        }
    }
}
