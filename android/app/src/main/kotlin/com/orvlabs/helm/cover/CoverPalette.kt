package com.orvlabs.helm.cover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.orvlabs.helm.ui.theme.H

/** Health: 0 calm, 0.5 watch, 1 needs a hand. */
@Composable
@ReadOnlyComposable
fun stateColor(h: Float): Color = if (h >= 1f) H.c.bad else if (h > 0f) H.c.warn else H.c.ok
