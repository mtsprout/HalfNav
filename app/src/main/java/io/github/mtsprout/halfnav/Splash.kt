package io.github.mtsprout.halfnav

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Which build this is, e.g. "Version 2.0 (4) debug". */
val buildLabel: String
    get() = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " debug" else ""

/** When it was built and from which commit; a trailing "+" means uncommitted changes. */
val buildDetail: String
    get() = "Built ${BuildConfig.BUILD_TIME} · ${BuildConfig.GIT_REV}"

/**
 * Shown for a moment when the app starts, so you can see which build is on the phone. It carries
 * on from Android's own launch screen (the icon's night sky), with the icon as the logo. Tap to skip.
 */
@Composable
fun VersionSplash(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(SPLASH_MILLIS)
        onDone()
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1B3D), Color(0xFF0A2A66))))
            .clickable(onClick = onDone),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The launcher icon: sunrise sky behind the road and arrow.
        val shape = RoundedCornerShape(40.dp)
        Box(Modifier.size(168.dp).shadow(16.dp, shape).clip(shape)) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(28.dp))
        Text("HalfNav", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(buildLabel, fontSize = 18.sp, color = Color(0xFFFFB347))
        Spacer(Modifier.height(4.dp))
        Text(buildDetail, fontSize = 14.sp, color = Color(0xFF9BF0FF))
    }
}

private const val SPLASH_MILLIS = 1800L
