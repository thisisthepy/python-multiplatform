@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package python.multiplatform.reflection

import kotlin.native.identityHashCode

internal actual fun identityHashOf(value: Any): Int = value.identityHashCode()
