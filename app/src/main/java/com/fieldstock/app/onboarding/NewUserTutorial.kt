package com.fieldstock.app.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldstock.app.*
import com.fieldstock.app.diagnostics.AppDiagnostics
import com.fieldstock.app.i18n.*
import kotlinx.coroutines.launch

private data class TutorialPage(val title: TextKey, val body: TextKey, val tip: TextKey)
private val pages = listOf(
    TutorialPage(TextKey.TUTORIAL_WAREHOUSE_TITLE, TextKey.TUTORIAL_WAREHOUSE_BODY, TextKey.TUTORIAL_WAREHOUSE_TIP),
    TutorialPage(TextKey.TUTORIAL_COUNT_TITLE, TextKey.TUTORIAL_COUNT_BODY, TextKey.TUTORIAL_COUNT_TIP),
    TutorialPage(TextKey.TUTORIAL_SCAN_TITLE, TextKey.TUTORIAL_SCAN_BODY, TextKey.TUTORIAL_SCAN_TIP),
    TutorialPage(TextKey.TUTORIAL_BACKUP_TITLE, TextKey.TUTORIAL_BACKUP_BODY, TextKey.TUTORIAL_BACKUP_TIP),
)

/** All examples are presentation-only: this screen never reads or writes inventory. */
@Composable
internal fun NewUserTutorial(replay: Boolean = false, onFinish: (skipped: Boolean) -> Unit) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    fun finish(skipped: Boolean) {
        AppDiagnostics.event(if (skipped) "tutorial.skipped" else "tutorial.completed", mapOf("replay" to replay, "step" to pager.currentPage + 1))
        onFinish(skipped)
    }
    BackHandler(enabled = !replay) {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
        else finish(true)
    }
    LaunchedEffect(Unit) { AppDiagnostics.event("tutorial.opened", mapOf("replay" to replay)) }
    LaunchedEffect(pager.currentPage) { AppDiagnostics.event("tutorial.step", mapOf("step" to pager.currentPage + 1)) }

    Surface(Modifier.fillMaxSize().testTag("tutorial"), color = Mist) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = Petrol) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(TextKey.FIELDSTOCK), fontSize = 13.sp, color = Color(0xFFBDD5DF))
                        Text(tr(TextKey.TUTORIAL_TITLE), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                    TextButton(onClick = { finish(true) }, modifier = Modifier.testTag("tutorial-skip")) {
                        Text(tr(TextKey.TUTORIAL_SKIP), color = Color.White)
                    }
                }
            }
            HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth().testTag("tutorial-pager")) { index ->
                val page = pages[index]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(24.dp).testTag("tutorial-page-$index"),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(tr(page.title), fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                                color = Petrol, modifier = Modifier.semantics { heading() })
                            Text(tr(page.body), fontSize = 16.sp, lineHeight = 24.sp, color = Ink)
                        }
                        TutorialPreview(index)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Teal, modifier = Modifier.size(20.dp))
                            Text(tr(page.tip), fontSize = 14.sp, lineHeight = 21.sp, color = Petrol)
                        }
                    }
                }
            }
            Surface(color = Color.White, shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(pages.size) { index ->
                                Box(Modifier.size(width = if (index == pager.currentPage) 22.dp else 7.dp, height = 7.dp)
                                    .clip(CircleShape).background(if (index == pager.currentPage) Blue else Color(0xFFD4DFE7)))
                            }
                        }
                        Text(tr(TextKey.TUTORIAL_STEP, pager.currentPage + 1, pages.size), fontSize = 12.sp,
                            color = Petrol, modifier = Modifier.testTag("tutorial-step"))
                    }
                    Row(Modifier.widthIn(max = 492.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (pager.currentPage > 0) OutlinedButton(onClick = {
                            scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                        }, enabled = !pager.isScrollInProgress, modifier = Modifier.heightIn(min = 52.dp).testTag("tutorial-back")) {
                            Text(tr(TextKey.TUTORIAL_BACK))
                        }
                        Button(onClick = {
                            if (last) finish(false)
                            else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        }, enabled = !pager.isScrollInProgress,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("tutorial-next")) {
                            Text(tr(if (last) TextKey.TUTORIAL_FINISH else TextKey.TUTORIAL_NEXT))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialPreview(index: Int) {
    // Decorative examples are excluded from accessibility; the surrounding text gives the instructions.
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr(TextKey.TUTORIAL_PREVIEW), fontSize = 11.sp, color = Color(0xFF587481))
            when (index) {
                0 -> {
                    TutorialAction("1", tr(TextKey.NEW_COUNTRY))
                    TutorialAction("2", tr(TextKey.NEW_WAREHOUSE))
                    TutorialAction("3", tr(TextKey.ENTER_WAREHOUSE), accent = true)
                }
                1 -> {
                    TutorialExample(tr(TextKey.PARTS), "BOM 000123A", tr(TextKey.TUTORIAL_QUANTITY_EXAMPLE))
                    HorizontalDivider(color = Mist)
                    TutorialExample(tr(TextKey.MACHINE), "SN AB01-0009", tr(TextKey.TUTORIAL_MACHINE_OPTIONAL))
                }
                2 -> {
                    Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                            val arm = 20.dp.toPx()
                            val stroke = 3.dp.toPx()
                            for (x in listOf(0f, size.width)) for (y in listOf(0f, size.height)) {
                                drawLine(Teal, Offset(x, y), Offset(x + if (x == 0f) arm else -arm, y), stroke)
                                drawLine(Teal, Offset(x, y), Offset(x, y + if (y == 0f) arm else -arm), stroke)
                            }
                        }
                        Text("AB01-0009", fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Petrol)
                    }
                    Text(tr(TextKey.TUTORIAL_SCAN_FLOW), fontSize = 13.sp, lineHeight = 20.sp, color = Teal)
                }
                else -> {
                    TutorialDestination(Icons.Default.List, tr(TextKey.RECORDS_3), tr(TextKey.TUTORIAL_RECORD_ACTIONS))
                    HorizontalDivider(color = Mist)
                    TutorialDestination(Icons.Default.Share, tr(TextKey.FILES), tr(TextKey.TUTORIAL_FILE_ACTIONS))
                }
            }
        }
    }
}

@Composable
private fun TutorialAction(number: String, label: String, accent: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (accent) Teal else Color(0xFFE5EFFC)), contentAlignment = Alignment.Center) {
            Text(number, color = if (accent) Color.White else Blue, fontWeight = FontWeight.Bold)
        }
        Text(label, fontSize = 16.sp, color = Petrol, fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun TutorialExample(label: String, identifier: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Teal, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(identifier, fontFamily = FontFamily.Monospace, color = Petrol, fontSize = 18.sp)
        Text(detail, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun TutorialDestination(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Blue, modifier = Modifier.size(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = Petrol, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
